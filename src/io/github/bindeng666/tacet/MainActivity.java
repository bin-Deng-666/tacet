package io.github.bindeng666.tacet;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private Config config;
    private LinearLayout page,apps,setupPanel;
    private TextView networkState,uiState,counters;
    private Switch master;
    private boolean rendering;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){updateStatus();handler.postDelayed(this,1500);}};
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private static final int TEXT=0xff302c29,MUTED=0xff898078,GREEN=0xff9b624b;
    @Override public void onCreate(Bundle state){super.onCreate(state);config=new Config(this);build();}
    @Override public void onResume(){super.onResume();if(config.enabled()&&config.network()&&!config.netApps().isEmpty()&&!FilterVpnService.running&&VpnService.prepare(this)==null&&!AppController.otherVpn(this))AppController.apply(this);handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override public void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onDestroy(){io.shutdownNow();super.onDestroy();}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(17),dp(12),dp(17),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(18));l.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(13);l.setLayoutParams(lp);return l;}
    private void button(LinearLayout p,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);
        boolean primary=label.startsWith("允许")||label.equals("完成保护设置");b.setTextColor(primary?Color.WHITE:TEXT);
        GradientDrawable bg=new GradientDrawable();bg.setColor(primary?GREEN:0xfff1eee8);bg.setCornerRadius(dp(10));b.setBackground(bg);b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(46));lp.topMargin=dp(4);lp.bottomMargin=dp(4);p.addView(b,lp);
    }
    private Switch toggle(LinearLayout parent,String title,String detail,boolean value,java.util.function.Consumer<Boolean> changed){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout labels=column();
        labels.addView(text(title,16,TEXT));if(!detail.isEmpty())labels.addView(text(detail,12,MUTED));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch sw=new Switch(this);sw.setContentDescription(title);sw.setChecked(value);row.addView(sw);row.setPadding(0,dp(5),0,dp(5));parent.addView(row);
        sw.setOnCheckedChangeListener((b,on)->{if(!rendering)changed.accept(on);});return sw;
    }
    private void build(){
        rendering=true;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff6f4ef);scroll.setClipToPadding(false);
        page=column();page.setPadding(dp(22),dp(20),dp(22),dp(24));page.setFocusableInTouchMode(true);page.requestFocus();scroll.addView(page);setContentView(scroll);
        page.setOnApplyWindowInsetsListener((v,in)->{android.graphics.Insets s=in.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(22),s.top+dp(18),dp(22),s.bottom+dp(20));return in;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("清净",24,TEXT);brand.setTypeface(Typeface.create("serif",Typeface.NORMAL));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView settings=text("设置",14,MUTED);settings.setPadding(dp(15),dp(12),0,dp(12));settings.setContentDescription("设置");settings.setOnClickListener(v->settingsDialog());header.addView(settings);page.addView(header);
        TextView tagline=text("让常用应用，少一点打扰。",14,MUTED);tagline.setPadding(0,dp(4),0,dp(22));page.addView(tagline);
        LinearLayout hero=card();hero.setPadding(dp(20),dp(20),dp(20),dp(20));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words=column();networkState=text("",23,TEXT);networkState.setTypeface(Typeface.create("serif",Typeface.NORMAL));words.addView(networkState);
        uiState=text("",13,MUTED);words.addView(uiState);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        master=new Switch(this);master.setContentDescription("广告过滤");master.setChecked(config.enabled());styleSwitch(master);row.addView(master);hero.addView(row);
        master.setOnCheckedChangeListener((v,on)->{if(rendering)return;if(on)enable();else{AppController.stop(this);updateStatus();}});
        page.addView(hero);
        setupPanel=card();page.addView(setupPanel);
        LinearLayout appHeader=new LinearLayout(this);appHeader.setGravity(Gravity.CENTER_VERTICAL);appHeader.setPadding(dp(2),dp(12),dp(2),dp(8));
        appHeader.addView(text("保护的应用",13,MUTED),new LinearLayout.LayoutParams(0,-2,1));TextView manage=text("管理",13,GREEN);manage.setPadding(dp(18),dp(10),0,dp(10));manage.setOnClickListener(v->chooseApp());appHeader.addView(manage);page.addView(appHeader);
        apps=column();page.addView(apps);renderApps();
        TextView foot=text("开启后自动生效，切回应用时也会继续保护。",12,MUTED);foot.setGravity(Gravity.CENTER);foot.setPadding(0,dp(16),0,dp(10));page.addView(foot);
        counters=text("",12,MUTED); // Detailed counters are shown in Settings only.
        rendering=false;updateStatus();
    }
    private void styleSwitch(Switch s){
        android.graphics.drawable.StateListDrawable track=new android.graphics.drawable.StateListDrawable();
        GradientDrawable on=new GradientDrawable();on.setColor(0xffa26950);on.setCornerRadius(dp(14));on.setSize(dp(46),dp(26));
        GradientDrawable off=new GradientDrawable();off.setColor(0xffd9d6d1);off.setCornerRadius(dp(14));off.setSize(dp(46),dp(26));
        track.addState(new int[]{android.R.attr.state_checked},on);track.addState(new int[]{},off);
        GradientDrawable thumb=new GradientDrawable();thumb.setColor(Color.WHITE);thumb.setShape(GradientDrawable.OVAL);thumb.setSize(dp(24),dp(24));thumb.setStroke(dp(.5f),0xffe7e2db);
        s.setTrackTintList(null);s.setThumbTintList(null);s.setTrackDrawable(track);s.setThumbDrawable(thumb);s.setSplitTrack(false);s.setSwitchMinWidth(dp(46));s.setThumbTextPadding(dp(1));
    }
    private void settingsDialog(){
        LinearLayout body=column();body.setPadding(dp(20),dp(6),dp(20),dp(10));
        body.addView(text("按你的习惯调整保护方式。",13,MUTED));
        toggle(body,"拦截广告请求","开启时使用系统的本地 VPN 功能",config.network(),on->{config.prefs.edit().putBoolean("network",on).apply();if(config.enabled()&&on)enable();else AppController.apply(this);});
        button(body,"应用保护选项",this::appOptions);
        button(body,"域名规则与白名单",this::rulesDialog);
        button(body,"运行记录",this::logsDialog);
        button(body,"检查保护状态",this::probe);
        button(body,"后台运行设置",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        body.addView(text("已拦截 "+config.prefs.getLong("dns_count",0)+" 次域名请求，自动跳过 "+config.prefs.getLong("ui_count",0)+" 次广告。",12,MUTED));
        body.addView(text("部分广告可能短暂出现。与正常内容混合返回的广告，可能仍需专用适配。界面内容只在本机处理。",12,MUTED));
        body.addView(text("清净 0.3.0",11,MUTED));
        ScrollView wrap=new ScrollView(this);wrap.addView(body);new AlertDialog.Builder(this).setTitle("设置").setView(wrap).setPositiveButton("完成",null).show();
    }
    private void appOptions(){
        LinearLayout body=column();body.setPadding(dp(20),dp(6),dp(20),dp(10));
        for(String pkg:config.apps()){
            toggle(body,label(pkg)+" · 自动跳过",pkg.equals(Config.BILI)?"只处理开屏和切回前台时的广告":pkg.equals(Config.YJB)?"识别并关闭广告跳过按钮":"尚无专用界面规则",config.prefs.getBoolean("ui:"+pkg,true),on->{config.prefs.edit().putBoolean("ui:"+pkg,on).apply();AdAccessibilityService.refreshActive();});
            toggle(body,label(pkg)+" · 拦截广告请求",pkg.equals(Config.BILI)?"默认关闭，不影响应用内内容和推荐":"拦截广告服务域名解析",config.net(pkg),on->{config.prefs.edit().putBoolean("net:"+pkg,on).apply();AppController.apply(this);updateStatus();});
        }
        ScrollView wrap=new ScrollView(this);wrap.addView(body);new AlertDialog.Builder(this).setTitle("应用保护选项").setView(wrap).setPositiveButton("完成",null).show();
    }
    private String label(String pkg){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){return "未安装的应用";}}
    private void renderApps(){
        apps.removeAllViews();Set<String> visible=config.prefs.getStringSet("visible_apps",config.defaultApps());Set<String> all=new HashSet<>(visible);all.addAll(config.apps());
        List<String> packages=new ArrayList<>(all);packages.sort(Comparator.comparing(this::label));LinearLayout group=card();group.setPadding(dp(16),dp(3),dp(16),dp(3));
        for(int i=0;i<packages.size();i++){
            String pkg=packages.get(i);LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(15),0,dp(15));
            ImageView icon=new ImageView(this);try{icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));}catch(Exception ignored){}row.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(36)));
            TextView name=text(label(pkg),16,TEXT);name.setPadding(dp(13),0,dp(8),0);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            Switch sw=new Switch(this);sw.setContentDescription(label(pkg));sw.setChecked(config.selected(pkg));styleSwitch(sw);sw.setOnCheckedChangeListener((b,on)->{config.setApp(pkg,on);AppController.apply(this);updateStatus();});row.addView(sw);group.addView(row);
            if(i+1<packages.size()){View line=new View(this);line.setBackgroundColor(0xffeeebe6);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(.6f));lp.leftMargin=dp(49);group.addView(line,lp);}
        }
        if(packages.isEmpty())group.addView(text("点击“管理”选择常用应用",13,MUTED));apps.addView(group);
    }
    private void chooseApp(){
        Intent i=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String,String> map=new TreeMap<>();for(ResolveInfo r:getPackageManager().queryIntentActivities(i,0))if(!r.activityInfo.packageName.equals(getPackageName()))map.put(r.activityInfo.packageName,r.loadLabel(getPackageManager()).toString());
        List<String> packages=new ArrayList<>(map.keySet());packages.sort(Comparator.comparing(map::get));String[] names=new String[packages.size()];boolean[] checks=new boolean[names.length];Set<String> selected=config.apps();
        for(int n=0;n<names.length;n++){names[n]=map.get(packages.get(n));checks[n]=selected.contains(packages.get(n));}
        new AlertDialog.Builder(this).setTitle("选择需要过滤的应用").setMultiChoiceItems(names,checks,(d,n,on)->checks[n]=on).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
            Set<String> result=new HashSet<>();for(int n=0;n<checks.length;n++)if(checks[n])result.add(packages.get(n));config.prefs.edit().putStringSet("apps",result).putStringSet("visible_apps",result).apply();AppController.apply(this);renderApps();updateStatus();
        }).show();
    }
    private void enable(){
        if(config.apps().isEmpty()){toast("请先选择应用");rendering=true;master.setChecked(false);rendering=false;return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);
        config.setEnabled(true);AdAccessibilityService.refreshActive();
        if(!config.network()){AppController.apply(this);updateStatus();return;}
        if(AppController.otherVpn(this)){
            new AlertDialog.Builder(this).setTitle("检测到其他 VPN").setMessage("Android 同时只能运行一个 VPN。网络过滤暂不启动，自动跳过仍可使用。先在原应用中关闭 VPN，再回来开启网络过滤。").setPositiveButton("知道了",(d,w)->updateStatus()).show();updateStatus();return;
        }
        Intent permission=VpnService.prepare(this);
        if(permission!=null)startActivityForResult(permission,42);else{AppController.apply(this);updateStatus();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42){if(result==RESULT_OK)AppController.apply(this);else toast("未授权网络过滤；自动跳过可单独使用");updateStatus();}}
    private void updateStatus(){
        if(config==null||networkState==null)return;rendering=true;master.setChecked(config.enabled());rendering=false;
        boolean on=config.enabled();int count=config.apps().size();boolean needsUi=on&&!AdAccessibilityService.isAllowed(this);boolean needsVpn=on&&config.network()&&!config.netApps().isEmpty()&&!FilterVpnService.running;
        networkState.setText(!on?"保护已暂停":count==0?"尚未选择应用":needsUi&&(!config.network()||needsVpn)?"等待完成设置":"正在保护");
        uiState.setText(!on?"随时开启，减少广告打扰":count==0?"先选择需要保护的应用":(needsUi||needsVpn)?"部分保护尚未开启":"已为 "+count+" 个应用开启保护");
        String reason=needsUi?"允许自动跳过，处理已经出现的广告。":needsVpn?(AppController.otherVpn(this)?"其他 VPN 正在运行，自动跳过仍可使用。":"允许广告拦截，减少广告加载。"):"";
        setupPanel.setVisibility(reason.isEmpty()?View.GONE:View.VISIBLE);
        if(!reason.equals(setupPanel.getTag())){setupPanel.setTag(reason);setupPanel.removeAllViews();if(!reason.isEmpty()){
            setupPanel.addView(text(reason,13,MUTED));button(setupPanel,needsUi?"允许自动跳过":AppController.otherVpn(this)?"查看连接设置":"完成保护设置",()->{
                if(needsUi)startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));else if(AppController.otherVpn(this))startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));else enable();
            });
        }}
        counters.setText("已拦截 "+config.prefs.getLong("dns_count",0)+" 次请求，跳过 "+config.prefs.getLong("ui_count",0)+" 次广告");
    }
    private void rulesDialog(){
        LinearLayout body=column();body.setPadding(dp(20),0,dp(20),0);
        Switch builtin=toggle(body,"内置广告域名规则","关闭后仅使用下面的自定义规则",config.builtin(),on->{});
        body.addView(text("额外拦截域名（每行一个）",13,TEXT));EditText blocks=new EditText(this);blocks.setText(config.prefs.getString("extra_block",""));blocks.setMinLines(3);blocks.setTextSize(13);blocks.setHint("ads.example.com");body.addView(blocks);
        body.addView(text("白名单优先（放行误拦的域名）",13,TEXT));EditText allow=new EditText(this);allow.setText(config.prefs.getString("allow",""));allow.setMinLines(3);allow.setTextSize(13);body.addView(allow);
        ScrollView wrap=new ScrollView(this);wrap.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("域名规则 · 本地保存").setView(wrap).setNegativeButton("取消",null).setNeutralButton("恢复默认",(d,w)->{config.prefs.edit().putBoolean("builtin",true).remove("extra_block").remove("allow").apply();toast("已恢复内置规则");}).setPositiveButton("保存",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            for(String s:Config.lines(blocks.getText()+"\n"+allow.getText()))if(DomainPolicy.normalize(s).isEmpty()){toast("域名格式不正确："+s);return;}
            config.prefs.edit().putBoolean("builtin",builtin.isChecked()).putString("extra_block",blocks.getText().toString()).putString("allow",allow.getText().toString()).apply();dialog.dismiss();toast("规则已保存");
        }));dialog.show();
    }
    private void logsDialog(){
        TextView body=text(RunLog.text(),12,TEXT);body.setPadding(dp(18),dp(10),dp(18),dp(10));body.setTextIsSelectable(true);ScrollView s=new ScrollView(this);s.addView(body);
        new AlertDialog.Builder(this).setTitle("运行记录 · 最近 100 条").setView(s).setPositiveButton("关闭",null).setNeutralButton("清空",(d,w)->RunLog.clear()).setNegativeButton("复制",(d,w)->{getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("清净运行记录",RunLog.text()));toast("已复制");}).show();
    }
    private void probe(){
        if(!FilterVpnService.running){toast("先开启网络过滤再检测");return;}
        ProgressDialog progress=ProgressDialog.show(this,"网络检测","正在检查拦截与正常域名…",true,false);
        io.execute(()->{
            String result;
            try{
                int blocked=dnsCode("doubleclick.net");int normal=dnsCode("www.baidu.com");
                result="广告域名 doubleclick.net："+(blocked==3?"已拦截":"未拦截（检查白名单）")+"\n正常域名 www.baidu.com："+(normal==0?"解析正常":"解析异常，返回码 "+normal)+"\n\n检测请求实际经过本机 VPN 接口。应用自带加密 DNS 和缓存广告不在此检测范围。";
            }catch(Exception e){result="检测失败："+e.getMessage();}
            String message=result;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;progress.dismiss();new AlertDialog.Builder(this).setTitle("网络检测结果").setMessage(message).setPositiveButton("确定",null).show();updateStatus();});
        });
    }
    private int dnsCode(String host)throws Exception{
        byte[] q=DnsMessage.query(host,new Random().nextInt(65535),1);
        try(DatagramSocket socket=new DatagramSocket()){
            socket.setSoTimeout(7000);socket.connect(InetAddress.getByName(FilterVpnService.DNS4),53);socket.send(new DatagramPacket(q,q.length));byte[] answer=new byte[4096];DatagramPacket p=new DatagramPacket(answer,answer.length);socket.receive(p);return answer[3]&15;
        }
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
