package io.github.bindeng666.tacet;

import android.content.*;
import android.net.VpnService;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        Config c=new Config(context);
        if(c.enabled()&&c.network()&&!c.netApps().isEmpty()&&VpnService.prepare(context)==null&&!AppController.otherVpn(context)){
            try{AppController.apply(context);}catch(RuntimeException e){RunLog.add(context,"自动恢复暂停","请打开清净恢复过滤："+e.getClass().getSimpleName());}
        }
    }
}
