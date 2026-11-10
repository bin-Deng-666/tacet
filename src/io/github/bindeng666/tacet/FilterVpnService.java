package io.github.bindeng666.tacet;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** A DNS-only local VPN. Normal app traffic stays on the original network. */
public final class FilterVpnService extends VpnService {
    public static final String START="io.github.bindeng666.tacet.START",STOP="io.github.bindeng666.tacet.STOP";
    public static final String DNS4="10.87.0.2", DNS6="fd87:ad:1::2";
    public static volatile boolean running;
    public static volatile String status="未开启";
    private static volatile FilterVpnService instance;
    public static void requestStop(Context c){
        FilterVpnService service=instance;
        if(service!=null)new Handler(Looper.getMainLooper()).post(service::shutdown);
        else c.stopService(new Intent(c,FilterVpnService.class));
    }
    private void shutdown(){closeTunnel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    private volatile boolean alive;
    private volatile long generation;
    private final ThreadLocal<Long> operationGeneration=new ThreadLocal<>();
    private ParcelFileDescriptor tunnel;
    private FileInputStream input;
    private FileOutputStream output;
    private Thread reader;
    private final Object writeLock=new Object();
    private final Map<String,TcpFlow> flows=new ConcurrentHashMap<>();
    private final Set<Closeable> sockets=ConcurrentHashMap.newKeySet();
    private ThreadPoolExecutor workers;
    private ScheduledExecutorService timer;
    private volatile DomainPolicy policy;
    private Config config;
    private Set<String> currentApps=Collections.emptySet();
    private String currentRules="";
    private ConnectivityManager cm;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changes=(p,key)->{
        if(key.equals("enabled")||key.equals("network")||key.equals("apps")||key.equals("allow")||key.equals("extra_block")||key.equals("builtin"))new Handler(Looper.getMainLooper()).post(this::configure);
    };
    @Override public void onCreate(){super.onCreate();instance=this;config=new Config(this);cm=getSystemService(ConnectivityManager.class);config.prefs.registerOnSharedPreferenceChangeListener(changes);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){AppController.stop(this);stopSelf();return START_NOT_STICKY;}
        if(!config.enabled()||!config.network()){stopSelf();return START_NOT_STICKY;}
        showNotification();configure();return START_STICKY;
    }
    private void showNotification(){
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("filter","广告过滤",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,FilterVpnService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"filter").setSmallIcon(R.drawable.ic_shield).setContentTitle("清净 · 广告过滤已开启")
            .setContentText("仅过滤已选应用的广告域名").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"关闭过滤",stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1,n);
    }
    private synchronized void configure(){
        if(!config.enabled()||!config.network()||config.netApps().isEmpty()){shutdown();return;}
        Set<String> selected=config.netApps();
        String rules=config.prefs.getString("extra_block","")+"|"+config.prefs.getString("allow","")+"|"+config.builtin();
        if(alive&&selected.equals(currentApps)){
            if(!rules.equals(currentRules)){policy=config.policy();currentRules=rules;RunLog.add(this,"规则已更新","白名单优先，立即用于后续域名查询");}
            return;
        }
        closeTunnel();
        try{
            policy=config.policy();currentRules=rules;currentApps=selected;
            Builder b=new Builder().setSession("清净 · 广告域名过滤").setMtu(1500)
                .addAddress("10.87.0.1",32).addRoute(DNS4,32).addDnsServer(DNS4)
                .addAddress("fd87:ad:1::1",128).addRoute(DNS6,128).addDnsServer(DNS6)
                .setBlocking(true).setMetered(false)
                .setConfigureIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE));
            int count=0;
            for(String pkg:selected)try{b.addAllowedApplication(pkg);count++;}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
            if(count==0){status="未找到所选应用";stopSelf();return;}
            b.addAllowedApplication(getPackageName()); // In-app connectivity diagnostics use the same tunnel.
            tunnel=b.establish();if(tunnel==null)throw new IOException("VPN permission unavailable");
            input=new FileInputStream(tunnel.getFileDescriptor());output=new FileOutputStream(tunnel.getFileDescriptor());
            workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(96),new ThreadPoolExecutor.AbortPolicy());
            timer=Executors.newSingleThreadScheduledExecutor();alive=true;running=true;status="运行中 · "+count+" 个应用";
            reader=new Thread(()->{operationGeneration.set(generation);readLoop();},"tacet-tun");reader.start();
            final long timerGeneration=generation;
            timer.scheduleAtFixedRate(()->{operationGeneration.set(timerGeneration);if(generation==timerGeneration)expireFlows();},1,1,TimeUnit.SECONDS);
            RunLog.add(this,"网络过滤开启",count+" 个应用；IPv4 / IPv6 DNS；普通连接不转发");
        }catch(Exception e){status="启动失败："+e.getMessage();RunLog.add(this,"网络过滤错误",status);closeTunnel();stopSelf();}
    }
    private void readLoop(){
        final FileInputStream stream=input;
        byte[] b=new byte[32767];
        try{
            while(alive&&stream==input){int n=stream.read(b);if(n<0)break;
                try{
                    IpPacket packet=IpPacket.parse(b,n);
                    if(packet.protocol==17)submit(()->processUdp(packet));else processTcp(packet);
                }catch(IOException ignored){}
            }
        }catch(IOException e){if(alive&&stream==input){RunLog.add(this,"网络过滤错误","本地接口已断开");new Handler(Looper.getMainLooper()).post(this::stopSelf);}}
    }
    private void submit(Runnable r){
        final long expected=generation;
        try{workers.execute(()->{if(!alive||generation!=expected)return;operationGeneration.set(expected);try{r.run();}finally{operationGeneration.remove();}});}catch(RejectedExecutionException ignored){}
    }
    private void processUdp(IpPacket p){
        try{
            DnsMessage query=DnsMessage.parse(p.data);byte[] answer=resolve(query,false);
            if(answer.length>1232){answer=query.error(0);answer[2]|=2;} // Let resolver retry over TCP rather than fragment.
            write(p.udpReply(answer));
        }catch(IOException ignored){}
    }
    private void write(byte[] b){synchronized(writeLock){Long expected=operationGeneration.get();if(!alive||output==null||(expected!=null&&expected!=generation))return;try{output.write(b);}catch(IOException ignored){}}}
    private Network physicalNetwork(){
        Network active=cm.getActiveNetwork();NetworkCapabilities ac=active==null?null:cm.getNetworkCapabilities(active);
        if(ac!=null&&!ac.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return active;
        Network fallback=null;
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities c=cm.getNetworkCapabilities(n);
            if(c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
                if(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))return n;fallback=n;
            }
        }
        return fallback;
    }
    private byte[] resolve(DnsMessage q,boolean tcp) throws IOException{
        // Re-check switch after queued work, so disabling cannot create new blocked answers.
        if(!config.enabled())return q.error(2);
        if(policy.blocks(q.name)){RunLog.add(this,"拦截域名",q.name);return q.error(3);}
        RunLog.add(this,"放行域名",q.name);
        Network network=physicalNetwork();
        List<InetAddress> servers=new ArrayList<>();
        if(network!=null){LinkProperties lp=cm.getLinkProperties(network);if(lp!=null)servers.addAll(lp.getDnsServers());}
        servers.add(InetAddress.getByName("223.5.5.5"));servers.add(InetAddress.getByName("119.29.29.29"));
        int attempts=0;
        for(InetAddress server:new LinkedHashSet<>(servers)){
            if(++attempts>3)break;
            try{return udpQuery(q,server,network);}catch(IOException ignored){}
        }
        RunLog.add(this,"域名查询失败",q.name+" · 上游 DNS 不可用");return q.error(2);
    }
    private byte[] udpQuery(DnsMessage q,InetAddress server,Network network)throws IOException{
        try(DatagramSocket s=new DatagramSocket(null)){
            sockets.add(s);
            try{
                if(!protect(s))throw new IOException("Cannot protect upstream socket");if(network!=null)network.bindSocket(s);
                s.bind(new InetSocketAddress(0));s.connect(server,53);s.setSoTimeout(1300);
                s.send(new DatagramPacket(q.wire,q.wire.length));byte[] b=new byte[65535];DatagramPacket r=new DatagramPacket(b,b.length);s.receive(r);
                byte[] result=Arrays.copyOf(b,r.getLength());if(!q.accepts(result))throw new IOException("Invalid DNS response");
                if((result[2]&2)!=0)return tcpQuery(q,server,network);return result;
            }finally{sockets.remove(s);}
        }
    }
    private byte[] tcpQuery(DnsMessage q,InetAddress server,Network network)throws IOException{
        try(Socket s=new Socket()){
            sockets.add(s);
            try{
                if(!protect(s))throw new IOException("Cannot protect upstream socket");if(network!=null)network.bindSocket(s);
                s.connect(new InetSocketAddress(server,53),1500);s.setSoTimeout(2000);
                DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeShort(q.wire.length);out.write(q.wire);out.flush();
                DataInputStream in=new DataInputStream(s.getInputStream());int n=in.readUnsignedShort();if(n<12)throw new IOException("Short DNS response");byte[] b=new byte[n];in.readFully(b);if(!q.accepts(b))throw new IOException("Mismatched DNS response");return b;
            }finally{sockets.remove(s);}
        }
    }
    private static final class Segment {final byte[] wire;final long end;Segment(byte[] b,long end){wire=b;this.end=end;}}
    private static final class TcpFlow {
        long clientNext,serverNext,touched,lastSend;boolean processing,fin;int retries;
        final ByteArrayOutputStream received=new ByteArrayOutputStream();final List<Segment> pending=new ArrayList<>();
    }
    private void processTcp(IpPacket p){
        String key=p.flowKey();TcpFlow f=flows.get(key);long now=SystemClock.elapsedRealtime();
        if((p.tcpFlags&4)!=0){flows.remove(key);return;}
        if((p.tcpFlags&2)!=0){
            if(f!=null){synchronized(f){for(Segment s:f.pending)write(s.wire);}return;}
            if(flows.size()>=64)return;
            f=new TcpFlow();f.clientNext=(p.sequence+1)&0xffffffffL;f.serverNext=ThreadLocalRandom.current().nextInt()&0xffffffffL;f.touched=now;
            byte[] syn=p.tcpReply(new byte[0],0x12,f.serverNext,f.clientNext);f.serverNext=(f.serverNext+1)&0xffffffffL;
            f.pending.add(new Segment(syn,f.serverNext));f.lastSend=now;flows.put(key,f);write(syn);return;
        }
        if(f==null)return;
        synchronized(f){
            f.touched=now;
            if((p.tcpFlags&16)!=0){long ack=p.acknowledgement;f.pending.removeIf(s->((ack-s.end)&0xffffffffL)<0x80000000L);}
            if(f.fin&&f.pending.isEmpty()&&p.data.length==0&&(p.tcpFlags&1)==0){flows.remove(key);return;}
            if(p.data.length>0){
                if(p.sequence!=f.clientNext){write(p.tcpReply(new byte[0],16,f.serverNext,f.clientNext));return;}
                if(f.received.size()+p.data.length>8192){write(p.tcpReply(new byte[0],4,f.serverNext,0));flows.remove(key);return;}
                f.received.write(p.data,0,p.data.length);f.clientNext=(f.clientNext+p.data.length)&0xffffffffL;
                write(p.tcpReply(new byte[0],16,f.serverNext,f.clientNext));byte[] data=f.received.toByteArray();
                if(!f.processing&&data.length>=2){int length=DnsMessage.u16(data,0);
                    if(length>0&&length<=8190&&data.length>=length+2){f.processing=true;TcpFlow flow=f;byte[] message=Arrays.copyOfRange(data,2,length+2);submit(()->replyTcp(p,flow,message));}
                }
            }
            if((p.tcpFlags&1)!=0){if(p.sequence+p.data.length==f.clientNext)f.clientNext=(f.clientNext+1)&0xffffffffL;write(p.tcpReply(new byte[0],16,f.serverNext,f.clientNext));if(!f.processing)flows.remove(key);}
        }
    }
    private void replyTcp(IpPacket p,TcpFlow f,byte[] message){
        try{
            byte[] answer=resolve(DnsMessage.parse(message),true);byte[] framed=new byte[answer.length+2];DnsMessage.put16(framed,0,answer.length);System.arraycopy(answer,0,framed,2,answer.length);
            synchronized(f){
                for(int off=0;off<framed.length;off+=1200){int n=Math.min(1200,framed.length-off);boolean last=off+n==framed.length;
                    byte[] b=p.tcpReply(Arrays.copyOfRange(framed,off,off+n),last?0x19:0x18,f.serverNext,f.clientNext);
                    f.serverNext=(f.serverNext+n+(last?1:0))&0xffffffffL;f.pending.add(new Segment(b,f.serverNext));write(b);
                }
                f.fin=true;f.lastSend=SystemClock.elapsedRealtime();f.retries=0;
            }
        }catch(IOException e){flows.remove(p.flowKey());write(p.tcpReply(new byte[0],4,0,0));}
    }
    private void expireFlows(){
        long now=SystemClock.elapsedRealtime();
        for(Map.Entry<String,TcpFlow> e:flows.entrySet()){
            TcpFlow f=e.getValue();synchronized(f){
                if(now-f.touched>20000||f.retries>3){flows.remove(e.getKey());continue;}
                if(!f.pending.isEmpty()&&now-f.lastSend>1500){for(Segment s:f.pending)write(s.wire);f.lastSend=now;f.retries++;}
            }
        }
    }
    private synchronized void closeTunnel(){
        alive=false;running=false;generation++;
        if(tunnel!=null)try{tunnel.close();}catch(IOException ignored){}tunnel=null;
        if(input!=null)try{input.close();}catch(IOException ignored){}input=null;
        synchronized(writeLock){if(output!=null)try{output.close();}catch(IOException ignored){}output=null;}
        for(Closeable s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();
        if(workers!=null)workers.shutdownNow();if(timer!=null)timer.shutdownNow();flows.clear();
        Thread t=reader;if(t!=null&&t!=Thread.currentThread())try{t.join(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}reader=null;
    }
    @Override protected void dump(FileDescriptor fd,PrintWriter writer,String[] args){
        writer.println("running="+running+"; status="+status+"; uiApps="+config.apps()+"; netApps="+config.netApps());
        writer.println("dnsBlocked="+config.prefs.getLong("dns_count",0)+"; adsSkipped="+config.prefs.getLong("ui_count",0));
        writer.println(RunLog.text());
    }
    @Override public void onRevoke(){status="VPN 授权已撤回或其他 VPN 已接管";RunLog.add(this,"网络过滤停止",status);stopSelf();}
    @Override public void onDestroy(){if(instance==this)instance=null;config.prefs.unregisterOnSharedPreferenceChangeListener(changes);closeTunnel();if(!status.startsWith("启动失败")&&!status.startsWith("VPN"))status="未开启";stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
