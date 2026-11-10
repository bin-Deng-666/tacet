package io.github.bindeng666.tacet;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.*;

public final class FilterTileService extends TileService {
    @Override public void onStartListening(){update();}
    private void update(){Tile t=getQsTile();if(t!=null){boolean on=new Config(this).enabled();t.setState(on?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);t.setLabel("广告过滤");if(Build.VERSION.SDK_INT>=29)t.setSubtitle(on?"已开启":"已关闭");t.updateTile();}}
    @Override public void onClick(){
        Config c=new Config(this);
        if(c.enabled())AppController.stop(this);
        else if(c.network()&&(VpnService.prepare(this)!=null||AppController.otherVpn(this))){
            Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,7,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));else startActivityAndCollapse(i);
        }else{c.setEnabled(true);AppController.apply(this);}
        update();
    }
}
