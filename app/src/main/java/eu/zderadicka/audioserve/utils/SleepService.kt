package eu.zderadicka.audioserve.utils


import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.*

import android.preference.PreferenceManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import android.util.Log
import eu.zderadicka.audioserve.AUDIOSERVICE_ACTION_PAUSE
import eu.zderadicka.audioserve.AudioService
import eu.zderadicka.audioserve.R
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.properties.Delegates
import android.media.MediaPlayer
import android.widget.RemoteViews
import android.widget.TextView
import eu.zderadicka.audioserve.notifications.NotificationsManager


const val SLEEP_START_ACTION = "eu.zderadicka.audioserve.SLEEP_START_ACTION"
const val SLEEP_EXTEND_ACTION = "eu.zderadicka.audioserve.SLEEP_EXTEND_ACTION"
const val SLEEP_CANCEL_ACTION = "eu.zderadicka.audioserve.SLEEP_CANCEL_ACTION"

private const val CHANNEL_ID = "eu.zderadicka.audioserve.sleep_timer.channel"
private const val minuteMillis = 60_000L
private const val NOTIFICATION_ID = 74211
private const val LOG_TAG = "SleepService"

private const val DEFAULT_SLEEP = 30
private const val DEFAULT_SLEEP_EXTEND = 15

private const val MIN_G_FOR_ACTION = 1.5F

fun currentSleepMins(ctx: Context) : Int {
    val sps = PreferenceManager.getDefaultSharedPreferences(ctx)
    var currentSleep = sps.getInt("pref_sleep", -1)
    if (currentSleep < 0)  {
        currentSleep = DEFAULT_SLEEP
        sps.edit().putInt("pref_sleep", currentSleep).commit()
    }

    return currentSleep
}

fun currentSleepExtendMins(ctx: Context) : Int {
    val sps = PreferenceManager.getDefaultSharedPreferences(ctx)
    var currentExtend = sps.getInt("pref_extend", -1)
    if (currentExtend < 0 ) {
        currentExtend = DEFAULT_SLEEP_EXTEND
        sps.edit().putInt("pref_extend", currentExtend).commit()
    }
    return currentExtend
}

fun cancelSleepTimer(context: Context) {
    val intent = Intent(context, SleepService::class.java)
    intent.action = SLEEP_CANCEL_ACTION
    context.startService(intent)
}

fun startSleepTimer(context: Context) {
    val intent = Intent(context, SleepService::class.java)
    intent.action = SLEEP_START_ACTION
    context.startService(intent)
}

fun extendSleepTimer(context: Context) {
    val intent = Intent(context, SleepService::class.java)
    intent.action = SLEEP_EXTEND_ACTION
    context.startService(intent)
}

class SleepService() : Service() {

    val remainsMins: Int
    get() {
        return if (timer?.remains?:-1>= 0) {
            timer?.remains?:0
        } else {
            0
        }
    }

    var statusListener: ((Boolean, Int) -> Unit)? = null
        set(v) {
            field = v
            v?.invoke(isRunning, 0)
        }

    private var timer: MyTimer? by Delegates.observable<MyTimer?>(null) {
        _, _, newValue ->
            statusListener?.invoke(newValue!= null, 0)
    }
    private lateinit var prefs: SharedPreferences



    val isRunning: Boolean
    get() {
        return timer!= null
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onDestroy() {
        statusListener = null
        super.onDestroy()
        Log.d(LOG_TAG, "SleepService destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SLEEP_START_ACTION -> {
                val time =  currentSleepMins(this)
                if (time > 0) {
                    timer = MyTimer(time )
                    statusListener?.invoke(isRunning, time)
                    timer?.startWithNotification()


                }

            }

            SLEEP_EXTEND_ACTION -> {

                extend()

            }

            SLEEP_CANCEL_ACTION -> {
                timer?.cancel()
                timer = null
                stopMe()

            }
        }
        return Service.START_NOT_STICKY
    }

    inner class LocalBinder: Binder() {
        val service: SleepService
            get (){
                return  this@SleepService
            }
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    private val notificationManager: NotificationManager by lazy {
        this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val name = "Audioserve Sleep Time"
            val description = "Audioserve sleep timer"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)

        }
    }

    private fun createNotification(mins: Int, extended:Boolean = false): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }
        val cancelIntent = Intent(this, SleepService::class.java)
        cancelIntent.action = SLEEP_CANCEL_ACTION
        val cancelPendingIntent = PendingIntent.getService(this,0,cancelIntent,0)

        val extendIntent = Intent(this, SleepService::class.java)
        extendIntent.action = SLEEP_EXTEND_ACTION
        val extendPendingIntent = PendingIntent.getService(this, 0, extendIntent, 0)

        val content = RemoteViews(packageName, R.layout.notif_sleep)
        content.setTextViewText(R.id.countDown, mins.toString())
        content.setOnClickPendingIntent(R.id.sleepNotification, NotificationsManager.createPendingIntentGeneral(this))

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(content)
                .setContentText(getString(R.string.sleep_notification_text))
                .setSmallIcon(R.drawable.ic_timer_white)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(R.drawable.ic_cancel,getString(R.string.cancel),cancelPendingIntent)
                .addAction(R.drawable.ic_timer, getString(R.string.extend), extendPendingIntent)

        val allowSound = prefs.getBoolean("pref_sleep_notification_sound", false)
        if (allowSound) {
            if (mins == 1) {
                startSound("android.resource://" + getPackageName() + "/" + R.raw.will_sleep_soon2)
            } else if (extended) {
                startSound("android.resource://" + getPackageName() + "/" + R.raw.extended)
            }
        }
            return builder.build()
    }

    fun stopMe() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopForeground(true)
        stopSelf()
    }

    private fun extend() {
            val time =  currentSleepExtendMins(this)
            if (time > 0 && timer != null) {
                val remains = timer?.remains?:0
                timer?.cancel()
                val totalTime = time+remains
                timer = MyTimer(totalTime)
                timer?.startWithNotification(true)

            }
    }

    var mediaPlayer: MediaPlayer?  = null
    var nextSound: Uri? = null

    private fun startSound(uriString: String) {

        // parse sound
        val soundUri: Uri
        try {
            soundUri = Uri.parse(uriString)
        } catch (e: Exception) {
            return
        }

        // play sound
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setOnPreparedListener({ mp -> mp.start() })
            mediaPlayer?.setOnCompletionListener{ mp ->
                mp.stop()
                mp.reset()
                if (nextSound!= null) {
                    mediaPlayer?.setDataSource(this, nextSound!!)
                    mediaPlayer?.prepareAsync()
                    nextSound = null

                }
            }
        }
        try {
            if (mediaPlayer?.isPlaying?:false) {
                nextSound = Uri.parse(uriString)
            } else {
                mediaPlayer?.setDataSource(this, soundUri)
                mediaPlayer?.prepareAsync()
            }
        } catch (e: Exception) {

        }

    }



    inner class MyTimer(val countDown:Int): CountDownTimer(minuteMillis* countDown, minuteMillis) {
        var remains: Int = -1
        private set
        private var startedExtended = false
        private val sensorManager = this@SleepService.getSystemService(Context.SENSOR_SERVICE) as SensorManager


        private val sensorsListener = object: SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

            }

            override fun onSensorChanged(event: SensorEvent) {
                val g = sqrt(
                        event.values.take(3)
                        .map {it /SensorManager.GRAVITY_EARTH}
                        .map { it*it}
                        .sum())

                if (g >= MIN_G_FOR_ACTION) {
                    Log.d(LOG_TAG, "Detectect motion of $g, extending sleep time")
                    extend()
                }


            }

        }

        private fun onDone() {
            sensorManager.unregisterListener(sensorsListener)
        }

        override fun cancel() {
            onDone()
            super.cancel()
        }

        fun startWithNotification(extended:Boolean = false) {
            if (extended) {
                startedExtended = true
            } else {
                startForeground(NOTIFICATION_ID, createNotification(countDown))
            }
            start()
        }

        private fun registerWithSensor() {
            sensorManager.registerListener(sensorsListener,
                    sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_NORMAL,
                    100_000
            )
        }

        override fun onFinish() {
            onDone()
            this@SleepService.timer = null
            val intent = Intent(this@SleepService, AudioService::class.java)
            intent.action = AUDIOSERVICE_ACTION_PAUSE
            startService(intent)
            stopMe()
        }

        override fun onTick(millisUntilFinished: Long) {
           remains = (millisUntilFinished.toDouble() / minuteMillis).roundToInt()
            statusListener?.invoke(isRunning, remains)
            if (remains == 1) registerWithSensor()
            Log.d(LOG_TAG, "Timer tick $millisUntilFinished $remains")
            notificationManager.notify(NOTIFICATION_ID, createNotification(remains, startedExtended))
            startedExtended = false
        }




    }

}



abstract class CountDownTimer
(
        /**
         * Millis since epoch when alarm should stop.
         */
        private val mMillisInFuture: Long,
        /**
         * The interval in millis that the user receives callbacks
         */
        private val mCountdownInterval: Long) {

    private var mStopTimeInFuture: Long = 0

    /**
     * boolean representing if the timer was cancelled
     */
    private var mCancelled = false


    // handles counting down
    private val mHandler = @SuppressLint("HandlerLeak") //TODO verify in future if leak is real
    object : Handler() {

        override fun handleMessage(msg: Message) {

            synchronized(this@CountDownTimer) {
                if (mCancelled) {
                    return
                }

                val millisLeft = mStopTimeInFuture - SystemClock.elapsedRealtime()

                if (millisLeft <= 0) {
                    onFinish()
                } else {
                    val lastTickStart = SystemClock.elapsedRealtime()
                    onTick(millisLeft)

                    // take into account user's onTick taking time to execute
                    val lastTickDuration = SystemClock.elapsedRealtime() - lastTickStart
                    var delay: Long

                    if (millisLeft < mCountdownInterval) {
                        // just delay until done
                        delay = millisLeft - lastTickDuration

                        // special case: user's onTick took more than interval to
                        // complete, trigger onFinish without delay
                        if (delay < 0) delay = 0
                    } else {
                        delay = mCountdownInterval - lastTickDuration

                        // special case: user's onTick took more than interval to
                        // complete, skip to next interval
                        while (delay < 0) delay += mCountdownInterval
                    }

                    sendMessageDelayed(obtainMessage(MSG), delay)
                }
            }
        }
    }

    /**
     * Cancel the countdown.
     */
    @Synchronized
    open fun cancel() {
        mCancelled = true
        mHandler.removeMessages(MSG)
    }

    /**
     * Start the countdown.
     */
    @Synchronized
    fun start(): CountDownTimer {
        mCancelled = false
        if (mMillisInFuture <= 0) {
            onFinish()
            return this
        }
        mStopTimeInFuture = SystemClock.elapsedRealtime() + mMillisInFuture
        mHandler.sendMessage(mHandler.obtainMessage(MSG))
        return this
    }


    /**
     * Callback fired on regular interval.
     * @param millisUntilFinished The amount of time until finished.
     */
    abstract fun onTick(millisUntilFinished: Long)

    /**
     * Callback fired when the time is up.
     */
    abstract fun onFinish()

    companion object {


        private val MSG = 1
    }
}

