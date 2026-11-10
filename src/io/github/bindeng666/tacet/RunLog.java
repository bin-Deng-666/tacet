package io.github.bindeng666.tacet;

import android.content.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class RunLog {
    private static final ArrayDeque<String> entries=new ArrayDeque<>();
    private static final SimpleDateFormat TIME=new SimpleDateFormat("HH:mm:ss",Locale.CHINA);
    public static synchronized void add(Context c,String category,String detail){
        entries.addFirst(TIME.format(new Date())+"  "+category+"  "+detail);
        while(entries.size()>100)entries.removeLast();
        android.util.Log.i("AdFilter",category+" "+detail);
        if(category.equals("拦截域名")||category.equals("跳过广告")){
            android.content.SharedPreferences p=new Config(c).prefs;
            String key=category.equals("拦截域名")?"dns_count":"ui_count";
            p.edit().putLong(key,p.getLong(key,0)+1).apply();
        }
    }
    public static synchronized String text(){return entries.isEmpty()?"暂无记录。开启过滤后使用目标应用，这里会显示本次运行的记录。":String.join("\n",entries);}
    public static synchronized void clear(){entries.clear();}
}
