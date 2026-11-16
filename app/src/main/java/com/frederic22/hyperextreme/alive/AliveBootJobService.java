package com.frederic22.hyperextreme.alive;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;

/**
 * Re-asserts the Alive wallpaper components after boot.
 *
 * <p>JobScheduler owns the lifecycle: the first attempt runs immediately, and failures are retried
 * with exponential backoff while root and WindowManager become ready. This avoids holding a
 * BroadcastReceiver's {@code goAsync()} for a minute (which can be killed as an ANR) and avoids a
 * hot fixed-interval loop of root helper executions.</p>
 */
public final class AliveBootJobService extends JobService {
    private static final int JOB_ID=0x0B007;
    private static final long[] RETRY_MS={1000L,2000L,4000L,8000L,16000L,30000L};

    static void schedule(Context context){schedule(context,0L,0);}

    static void schedule(Context context,long delayMs){schedule(context,delayMs,0);}

    static void schedule(Context context,long delayMs,int attempt){
        try {
            JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if(scheduler==null)return;
            PersistableBundle extras=new PersistableBundle();
            extras.putInt("attempt",attempt);
            JobInfo.Builder builder=new JobInfo.Builder(JOB_ID,
                    new ComponentName(context,AliveBootJobService.class))
                    .setMinimumLatency(Math.max(0L,delayMs))
                    .setOverrideDeadline(Math.max(2000L,delayMs+5000L))
                    .setExtras(extras);
            scheduler.schedule(builder.build());
        }catch(Throwable error){
            Log.w(Protocol.TAG,"Alive boot job: schedule failed ("+error+")");
        }
    }

    @Override
    public boolean onStartJob(JobParameters params){
        final Context app=getApplicationContext();
        final int attempt=params.getExtras()==null?0:params.getExtras().getInt("attempt",0);
        new Thread(()->{
            boolean ok=false;
            try {
                if(AliveBootReceiver.shouldKeepWallpaper(app)){
                    RootBridge.Result result=RootBridge.wallpaper(app,true);
                    ok=result.ok()&&result.text!=null&&result.text.contains("PAIR_VERIFIED");
                    Log.i(Protocol.TAG,"Alive boot job #"+attempt+": exit="+result.code+" ok="+ok);
                }else{
                    ok=true;
                    Log.i(Protocol.TAG,"Alive boot job #"+attempt+": skipped (wallpaper changed)");
                }
            }catch(Throwable error){
                Log.i(Protocol.TAG,"Alive boot job #"+attempt+" failed: "+error);
            }
            jobFinished(params,false);
            if(!ok&&attempt+1<RETRY_MS.length)schedule(app,RETRY_MS[attempt+1],attempt+1);
        },"AliveBootJob").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params){return false;}
}
