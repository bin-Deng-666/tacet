package io.github.bindeng666.tacet.test;

import android.app.*;
import android.content.*;
import android.os.*;
import io.github.bindeng666.tacet.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** Real TUN-interface tests; installed separately and removed after verification. */
public final class DeviceTests extends Instrumentation {
    private int checks;
    private Context context;
    private Config config;
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;android.util.Log.i("AdFilterTest","PASS "+message);}
    private void waitFor(java.util.function.BooleanSupplier condition,String message)throws Exception{
        long end=SystemClock.elapsedRealtime()+7000;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<end)Thread.sleep(70);check(condition.getAsBoolean(),message);
    }
    private boolean vpnRouted(){android.net.ConnectivityManager cm=context.getSystemService(android.net.ConnectivityManager.class);android.net.Network n=cm.getActiveNetwork();android.net.NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);return caps!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);}
    private int udp(String host,String ip)throws Exception{
        byte[] q=DnsMessage.query(host,new Random().nextInt(65535),1);
        try(DatagramSocket s=new DatagramSocket()){
            s.setSoTimeout(6000);s.connect(InetAddress.getByName(ip),53);s.send(new DatagramPacket(q,q.length));byte[] b=new byte[8192];DatagramPacket p=new DatagramPacket(b,b.length);s.receive(p);
            check(DnsMessage.parse(q).accepts(Arrays.copyOf(b,p.getLength())),"UDP response matches question "+ip);return b[3]&15;
        }
    }
    private int tcp(String host,String ip)throws Exception{
        byte[] q=DnsMessage.query(host,new Random().nextInt(65535),1);
        try(Socket s=new Socket()){
            s.connect(new InetSocketAddress(InetAddress.getByName(ip),53),4000);s.setSoTimeout(6000);
            DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeShort(q.length);out.write(q);out.flush();
            DataInputStream in=new DataInputStream(s.getInputStream());byte[] b=new byte[in.readUnsignedShort()];in.readFully(b);check(DnsMessage.parse(q).accepts(b),"TCP response matches question "+ip);return b[3]&15;
        }
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        context=getTargetContext();config=new Config(context);Bundle result=new Bundle();
        String savedBlock=config.prefs.getString("extra_block",""),savedAllow=config.prefs.getString("allow","");Set<String> apps=config.apps();
        try{
            Intent activity=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivitySync(activity);
            runOnMainSync(()->{config.prefs.edit().putBoolean("enabled",true).putBoolean("network",true).putBoolean("builtin",true).apply();AppController.apply(context);});
            waitFor(()->FilterVpnService.running,"VPN starts with permission");
            android.net.ConnectivityManager connectivity=context.getSystemService(android.net.ConnectivityManager.class);
            waitFor(()->{android.net.Network n=connectivity.getActiveNetwork();android.net.NetworkCapabilities caps=n==null?null:connectivity.getNetworkCapabilities(n);return caps!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);},"System routes selected UID through VPN");
            check(udp("doubleclick.net",FilterVpnService.DNS4)==3,"IPv4 UDP ad blocked");
            check(udp("www.baidu.com",FilterVpnService.DNS4)==0,"IPv4 UDP normal DNS works");
            check(udp("doubleclick.net",FilterVpnService.DNS6)==3,"IPv6 UDP ad blocked");
            check(udp("www.baidu.com",FilterVpnService.DNS6)==0,"IPv6 UDP normal DNS works");
            check(tcp("doubleclick.net",FilterVpnService.DNS4)==3,"IPv4 TCP ad blocked");
            check(tcp("www.baidu.com",FilterVpnService.DNS4)==0,"IPv4 TCP normal DNS works");
            check(tcp("doubleclick.net",FilterVpnService.DNS6)==3,"IPv6 TCP ad blocked");
            check(tcp("www.baidu.com",FilterVpnService.DNS6)==0,"IPv6 TCP normal DNS works");
            runOnMainSync(()->config.prefs.edit().putString("extra_block","www.baidu.com").putString("allow","").apply());Thread.sleep(300);
            check(udp("www.baidu.com",FilterVpnService.DNS4)==3,"Edited block rule applies live");
            runOnMainSync(()->config.prefs.edit().putString("allow","www.baidu.com").apply());Thread.sleep(300);
            check(udp("www.baidu.com",FilterVpnService.DNS4)==0,"Whitelist takes precedence live");
            runOnMainSync(()->config.prefs.edit().putString("extra_block",savedBlock).putString("allow",savedAllow).apply());Thread.sleep(200);
            for(int i=0;i<3;i++){
                runOnMainSync(()->AppController.stop(context));waitFor(()->!FilterVpnService.running,"Switch OFF closes VPN "+i);
                waitFor(()->!vpnRouted(),"System detaches closed tunnel "+i);
                runOnMainSync(()->{config.setEnabled(true);AppController.apply(context);});waitFor(()->FilterVpnService.running,"Switch ON restores VPN "+i);
                waitFor(this::vpnRouted,"System routes reopened tunnel "+i);
                check(udp("doubleclick.net",FilterVpnService.DNS4)==3,"Reopened tunnel still blocks "+i);
            }
            runOnMainSync(()->{config.setApp(Config.YJB,false);AppController.apply(context);});Thread.sleep(600);
            check(udp("www.baidu.com",FilterVpnService.DNS4)==0,"Per-app change rebuilds tunnel without breaking DNS");
            runOnMainSync(()->{config.prefs.edit().putStringSet("apps",apps).apply();AppController.apply(context);});Thread.sleep(600);
            check(udp("www.baidu.com",FilterVpnService.DNS4)==0,"App selection restores without breaking DNS");
            result.putString("stream","PASS: "+checks+" real-device checks\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAILED after "+checks+" checks: "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
        finally{config.prefs.edit().putString("extra_block",savedBlock).putString("allow",savedAllow).putStringSet("apps",apps).apply();}
    }
}
