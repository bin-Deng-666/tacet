package io.github.bindeng666.tacet;

import android.content.*;
import android.net.*;
import android.os.Build;

public final class AppController {
    private AppController(){}
    public static boolean otherVpn(Context c){
        if(FilterVpnService.running)return false;
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities nc=cm.getNetworkCapabilities(n);
            if(nc!=null&&nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&nc.getOwnerUid()!=android.os.Process.myUid())return true;
        }
        return false;
    }
    public static void apply(Context c){
        Config config=new Config(c);
        if(config.enabled()&&config.network()&&!config.netApps().isEmpty()){
            if(otherVpn(c)){RunLog.add(c,"网络过滤暂停","检测到其他 VPN；自动跳过仍可运行");return;}
            if(VpnService.prepare(c)!=null){RunLog.add(c,"网络过滤待授权","请打开清净并允许本地 VPN");return;}
            Intent i=new Intent(c,FilterVpnService.class).setAction(FilterVpnService.START);
            if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
        }else FilterVpnService.requestStop(c);
        AdAccessibilityService.refreshActive();
        android.service.quicksettings.TileService.requestListeningState(c,new ComponentName(c,FilterTileService.class));
    }
    public static void stop(Context c){new Config(c).setEnabled(false);FilterVpnService.requestStop(c);AdAccessibilityService.refreshActive();android.service.quicksettings.TileService.requestListeningState(c,new ComponentName(c,FilterTileService.class));}
}
