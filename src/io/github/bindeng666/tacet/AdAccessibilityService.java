package io.github.bindeng666.tacet;

import android.accessibilityservice.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.accessibility.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Event-driven local matching. No screenshots, network requests or LLM are involved. */
public final class AdAccessibilityService extends AccessibilityService {
    public static volatile boolean connected;
    public static boolean isAllowed(Context context){
        try{
            String value=android.provider.Settings.Secure.getString(context.getContentResolver(),android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(value!=null)for(String entry:value.split(":")){
                ComponentName name=ComponentName.unflattenFromString(entry);
                if(name!=null&&name.getPackageName().equals(context.getPackageName())&&name.getClassName().equals(AdAccessibilityService.class.getName()))return true;
            }
        }catch(SecurityException ignored){}
        android.view.accessibility.AccessibilityManager manager=context.getSystemService(android.view.accessibility.AccessibilityManager.class);
        for(AccessibilityServiceInfo info:manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)){
            android.content.pm.ServiceInfo service=info.getResolveInfo().serviceInfo;
            if(service.packageName.equals(context.getPackageName())&&service.name.equals(AdAccessibilityService.class.getName()))return true;
        }
        return connected;
    }
    private static AdAccessibilityService active;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable scanner=this::scan;
    private Config config;
    private final List<Rule> rules=new ArrayList<>();
    private final Map<String,Long> lastAction=new HashMap<>();
    private String foreground="",activity="";
    private long sessionStart,windowBudgetStart;
    private int actions;
    private boolean scheduled;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changed=(p,k)->{
        if(k.equals("enabled")||k.equals("apps")||k.startsWith("ui:"))handler.post(this::refresh);
    };
    private static final class Rule {
        String id,pkg,name,activity,action,position;Set<String> ids=new HashSet<>(),ancestors=new HashSet<>();Pattern text;boolean marker;
        Rule(JSONObject o)throws JSONException{
            id=o.getString("id");pkg=o.getString("package");name=o.getString("name");activity=o.optString("activityContains");action=o.optString("action","click");position=o.optString("position");marker=o.optBoolean("requireAdMarker");
            JSONArray a=o.optJSONArray("viewIds");if(a!=null)for(int i=0;i<a.length();i++)ids.add(a.getString(i));
            a=o.optJSONArray("ancestorIds");if(a!=null)for(int i=0;i<a.length();i++)ancestors.add(a.getString(i));
            if(o.has("textPattern"))text=Pattern.compile(o.getString("textPattern"));
        }
    }
    @Override public void onServiceConnected(){
        active=this;connected=true;config=new Config(this);
        config.prefs.registerOnSharedPreferenceChangeListener(changed);
        try(InputStream in=getAssets().open("ui-rules.json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            rules.clear();
            JSONArray array=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getJSONArray("rules");
            for(int i=0;i<array.length();i++)rules.add(new Rule(array.getJSONObject(i)));
        }catch(Exception e){RunLog.add(this,"界面规则错误",e.toString());}
        refresh();RunLog.add(this,"无障碍服务就绪","界面只在本机匹配；仅操作所选应用");
    }
    public static void refreshActive(){AdAccessibilityService s=active;if(s!=null)s.handler.post(s::refresh);}
    private void refresh(){
        handler.removeCallbacksAndMessages(null);scheduled=false;
        AccessibilityServiceInfo info=getServiceInfo();
        if(info!=null){info.packageNames=null;info.eventTypes=config.enabled()?AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED|AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED|AccessibilityEvent.TYPE_WINDOWS_CHANGED:AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;setServiceInfo(info);}
        if(config.enabled())schedule(150);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(config==null||!config.enabled())return;
        String pkg=e.getPackageName()==null?"":e.getPackageName().toString();
        int type=e.getEventType();
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            if(!pkg.equals(foreground)&&!pkg.isEmpty()){
                foreground=pkg;sessionStart=SystemClock.elapsedRealtime();actions=0;lastAction.clear();
            }
            if(e.getClassName()!=null)activity=e.getClassName().toString();
        }
        if(config.ui(pkg)||type==AccessibilityEvent.TYPE_WINDOWS_CHANGED){
            schedule(120);
            if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){handler.postDelayed(()->schedule(0),650);handler.postDelayed(()->schedule(0),1600);handler.postDelayed(()->schedule(0),3500);}
        }
    }
    private void schedule(long ms){if(!scheduled){scheduled=true;handler.postDelayed(scanner,ms);}}
    private static String id(AccessibilityNodeInfo n){String s=n.getViewIdResourceName();if(s==null)return "";int slash=s.lastIndexOf('/');return slash>=0?s.substring(slash+1):s;}
    private static String text(AccessibilityNodeInfo n){CharSequence t=n.getText(),d=n.getContentDescription();return t!=null?t.toString():(d==null?"":d.toString());}
    private List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root){
        List<AccessibilityNodeInfo> out=new ArrayList<>();ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);
        while(!queue.isEmpty()&&out.size()<1600){AccessibilityNodeInfo n=queue.removeFirst();out.add(n);for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)queue.addLast(c);}}
        return out;
    }
    private void scan(){
        scheduled=false;if(config==null||!config.enabled())return;
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null||root.getPackageName()==null)return;
        String pkg=root.getPackageName().toString();if(!config.ui(pkg))return;
        if(!pkg.equals(foreground)){foreground=pkg;sessionStart=SystemClock.elapsedRealtime();actions=0;lastAction.clear();}
        long now=SystemClock.elapsedRealtime();
        // Bound automated actions per foreground session; the next foreground session gets a fresh budget.
        if(now-windowBudgetStart>60000){windowBudgetStart=now;actions=0;}if(actions>=12)return;
        List<AccessibilityNodeInfo> all=nodes(root);
        for(Rule r:rules){
            if(!r.pkg.equals(pkg)||(!r.activity.isEmpty()&&!activity.contains(r.activity)))continue;
            long last=lastAction.getOrDefault(r.id,0L);if(now-last<1600)continue;
            for(AccessibilityNodeInfo n:all){
                if(!n.isVisibleToUser()||!n.isEnabled()||(!r.ids.isEmpty()&&!r.ids.contains(id(n)))||(r.text!=null&&!r.text.matcher(text(n)).matches()))continue;
                if(!r.ancestors.isEmpty()&&!hasAncestor(n,r.ancestors,r.marker))continue;
                if("topRight".equals(r.position)){
                    Rect b=new Rect(),screen=new Rect();n.getBoundsInScreen(b);root.getBoundsInScreen(screen);
                    if(b.left<screen.width()/2||b.top>screen.height()/3||b.isEmpty())continue;
                }
                if(click(n,true)){
                    lastAction.put(r.id,now);actions++;
                    RunLog.add(this,"跳过广告",(pkg.equals(Config.BILI)?"哔哩哔哩":"养基宝")+" · "+r.name);schedule(500);
                    return;
                }
            }
        }
    }
    private boolean hasAncestor(AccessibilityNodeInfo n,Set<String> ids,boolean requireMarker){
        AccessibilityNodeInfo parent=n.getParent();
        for(int i=0;i<6&&parent!=null;i++,parent=parent.getParent()){
            if(ids.contains(id(parent))||ids.contains(parent.getViewIdResourceName())){
                if(!requireMarker)return true;
                for(AccessibilityNodeInfo child:nodes(parent)){
                    String t=text(child),v=id(child);
                    if(t.equals("广告")||t.startsWith("广告·")||v.equals("ad_goods_mark_big")||v.equals("ad_tag")||v.equals("ad_tint_frame"))return true;
                }
            }
        }
        return false;
    }
    private boolean click(AccessibilityNodeInfo node,boolean gestureAllowed){
        AccessibilityNodeInfo n=node;
        for(int i=0;i<3&&n!=null;i++,n=n.getParent())if(n.isClickable()&&n.isVisibleToUser()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
        if(!gestureAllowed)return false;
        Rect rect=new Rect();node.getBoundsInScreen(rect);
        if(rect.isEmpty()||rect.width()>getResources().getDisplayMetrics().widthPixels/2||rect.height()>getResources().getDisplayMetrics().heightPixels/4)return false;
        Path path=new Path();path.moveTo(rect.exactCenterX(),rect.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,60)).build(),null,null);
    }
    @Override public void onInterrupt(){handler.removeCallbacksAndMessages(null);scheduled=false;}
    @Override public void onDestroy(){if(active==this){connected=false;active=null;}if(config!=null)config.prefs.unregisterOnSharedPreferenceChangeListener(changed);handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
