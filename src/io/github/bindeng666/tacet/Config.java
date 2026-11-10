package io.github.bindeng666.tacet;

import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Config {
    public static final String BILI="tv.danmaku.bili", YJB="yangjibao.app";
    private final Context context;
    public final SharedPreferences prefs;
    public Config(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("config",0);}
    public boolean enabled(){return prefs.getBoolean("enabled",false);}
    public boolean network(){return prefs.getBoolean("network",true);}
    public void setEnabled(boolean value){prefs.edit().putBoolean("enabled",value).apply();}
    public Set<String> defaultApps(){
        Set<String> installed=new HashSet<>();for(String pkg:Arrays.asList(BILI,YJB))try{context.getPackageManager().getApplicationInfo(pkg,0);installed.add(pkg);}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}return installed;
    }
    public Set<String> apps(){return new HashSet<>(prefs.getStringSet("apps",defaultApps()));}
    public void setApp(String pkg,boolean on){Set<String>s=apps();if(on)s.add(pkg);else s.remove(pkg);prefs.edit().putStringSet("apps",s).apply();}
    public boolean selected(String pkg){return apps().contains(pkg);}
    public boolean ui(String pkg){return selected(pkg)&&prefs.getBoolean("ui:"+pkg,true);}
    /** Network filtering is opt-out per app; Bilibili defaults to off so in-app content is untouched. */
    public boolean net(String pkg){return selected(pkg)&&prefs.getBoolean("net:"+pkg,!pkg.equals(BILI));}
    public Set<String> netApps(){Set<String> out=new HashSet<>();for(String pkg:apps())if(net(pkg))out.add(pkg);return out;}
    public boolean builtin(){return prefs.getBoolean("builtin",true);}
    public DomainPolicy policy(){
        List<String> blocks=new ArrayList<>(),allows=new ArrayList<>();
        if(builtin())try(InputStream in=context.getAssets().open("domains.json")){
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1)buffer.write(chunk,0,n);
            JSONObject o=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            JSONArray a=o.getJSONArray("blockedDomains");for(int i=0;i<a.length();i++)blocks.add(a.getString(i));
        }catch(Exception e){throw new IllegalStateException("Cannot load built-in rules",e);}
        blocks.addAll(lines(prefs.getString("extra_block","")));allows.addAll(lines(prefs.getString("allow","")));
        return new DomainPolicy(blocks,allows);
    }
    public static List<String> lines(String text){List<String> out=new ArrayList<>();for(String s:text.split("[\\s,]+"))if(!s.isEmpty())out.add(s);return out;}
}
