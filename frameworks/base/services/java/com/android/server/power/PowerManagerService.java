/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.power;

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.server.BatteryService;
import com.android.server.EventLogTags;
import com.android.server.LightsService;
import com.android.server.TwilightService;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.display.DisplayManagerService;
import com.android.server.dreams.DreamManagerService;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.SensorManager;
import android.hardware.SystemSensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemService;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.WindowManagerPolicy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import libcore.util.Objects;

/**
 * The power manager service is responsible for coordinating power management
 * functions on the device.
 */
public final class PowerManagerService extends IPowerManager.Stub
        implements Watchdog.Monitor {
    private static final String TAG = "PowerManagerService";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SPEW = DEBUG && true;

    // Message: Sent when a user activity timeout occurs to update the power state.
    private static final int MSG_USER_ACTIVITY_TIMEOUT = 1;
    // Message: Sent when the device enters or exits a napping or dreaming state.
    private static final int MSG_SANDMAN = 2;
    // Message: Sent when the screen on blocker is released.
    private static final int MSG_SCREEN_ON_BLOCKER_RELEASED = 3;
    // Message: Sent to poll whether the boot animation has terminated.
    private static final int MSG_CHECK_IF_BOOT_ANIMATION_FINISHED = 4;

    // Dirty bit: mWakeLocks changed
    private static final int DIRTY_WAKE_LOCKS = 1 << 0;
    // Dirty bit: mWakefulness changed
    private static final int DIRTY_WAKEFULNESS = 1 << 1;
    // Dirty bit: user activity was poked or may have timed out
    private static final int DIRTY_USER_ACTIVITY = 1 << 2;
    // Dirty bit: actual display power state was updated asynchronously
    private static final int DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED = 1 << 3;
    // Dirty bit: mBootCompleted changed
    private static final int DIRTY_BOOT_COMPLETED = 1 << 4;
    // Dirty bit: settings changed
    private static final int DIRTY_SETTINGS = 1 << 5;
    // Dirty bit: mIsPowered changed
    private static final int DIRTY_IS_POWERED = 1 << 6;
    // Dirty bit: mStayOn changed
    private static final int DIRTY_STAY_ON = 1 << 7;
    // Dirty bit: battery state changed
    private static final int DIRTY_BATTERY_STATE = 1 << 8;
    // Dirty bit: proximity state changed
    private static final int DIRTY_PROXIMITY_POSITIVE = 1 << 9;
    // Dirty bit: screen on blocker state became held or unheld
    private static final int DIRTY_SCREEN_ON_BLOCKER_RELEASED = 1 << 10;
    // Dirty bit: dock state changed
    private static final int DIRTY_DOCK_STATE = 1 << 11;

    // Wakefulness: The device is asleep and can only be awoken by a call to wakeUp().
    // The screen should be off or in the process of being turned off by the display controller.
    private static final int WAKEFULNESS_ASLEEP = 0;//睡眠状态，此时灭屏
    // Wakefulness: The device is fully awake.  It can be put to sleep by a call to goToSleep().
    // When the user activity timeout expires, the device may start napping or go to sleep.
    private static final int WAKEFULNESS_AWAKE = 1;//屏幕亮
    // Wakefulness: The device is napping.  It is deciding whether to dream or go to sleep
    // but hasn't gotten around to it yet.  It can be awoken by a call to wakeUp(), which
    // ends the nap. User activity may brighten the screen but does not end the nap.
    private static final int WAKEFULNESS_NAPPING = 2;//设备正在打盹
    // Wakefulness: The device is dreaming.  It can be awoken by a call to wakeUp(),
    // which ends the dream.  The device goes to sleep when goToSleep() is called, when
    // the dream ends or when unplugged.
    // User activity may brighten the screen but does not end the dream.
    private static final int WAKEFULNESS_DREAMING = 3;////屏保

    // Summarizes the state of all active wakelocks.
    private static final int WAKE_LOCK_CPU = 1 << 0;
    private static final int WAKE_LOCK_SCREEN_BRIGHT = 1 << 1;
    private static final int WAKE_LOCK_SCREEN_DIM = 1 << 2;
    private static final int WAKE_LOCK_BUTTON_BRIGHT = 1 << 3;
    private static final int WAKE_LOCK_PROXIMITY_SCREEN_OFF = 1 << 4;
    private static final int WAKE_LOCK_STAY_AWAKE = 1 << 5; // only set if already awake

    // Summarizes the user activity state.
    private static final int USER_ACTIVITY_SCREEN_BRIGHT = 1 << 0;
    private static final int USER_ACTIVITY_SCREEN_DIM = 1 << 1;

    // Default and minimum screen off timeout in milliseconds.
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT = 15 * 1000;
    private static final int MINIMUM_SCREEN_OFF_TIMEOUT = 10 * 1000;

    // The screen dim duration, in milliseconds.
    // This is subtracted from the end of the screen off timeout so the
    // minimum screen off timeout should be longer than this.
    private static final int SCREEN_DIM_DURATION = 7 * 1000;

    // The maximum screen dim time expressed as a ratio relative to the screen
    // off timeout.  If the screen off timeout is very short then we want the
    // dim timeout to also be quite short so that most of the time is spent on.
    // Otherwise the user won't get much screen on time before dimming occurs.
    private static final float MAXIMUM_SCREEN_DIM_RATIO = 0.2f;

    // The name of the boot animation service in init.rc.
    private static final String BOOT_ANIMATION_SERVICE = "bootanim";

    // Poll interval in milliseconds for watching boot animation finished.
    private static final int BOOT_ANIMATION_POLL_INTERVAL = 200;

    // If the battery level drops by this percentage and the user activity timeout
    // has expired, then assume the device is receiving insufficient current to charge
    // effectively and terminate the dream.
    private static final int DREAM_BATTERY_LEVEL_DRAIN_CUTOFF = 5;

    private Context mContext;
    private LightsService mLightsService;
    private BatteryService mBatteryService;
    private DisplayManagerService mDisplayManagerService;
    private IBatteryStats mBatteryStats;
    private IAppOpsService mAppOps;
    private HandlerThread mHandlerThread;
    private PowerManagerHandler mHandler;
    private WindowManagerPolicy mPolicy;
    private Notifier mNotifier;
    private DisplayPowerController mDisplayPowerController;
    private WirelessChargerDetector mWirelessChargerDetector;
    private SettingsObserver mSettingsObserver;
    private DreamManagerService mDreamManager;
    private LightsService.Light mAttentionLight;

    private final Object mLock = new Object();

    // A bitfield that indicates what parts of the power state have
    // changed and need to be recalculated.
    //一个位字段，指示电源状态的哪些部分发生了更改，需要重新计算。
    private int mDirty;

    // Indicates whether the device is awake or asleep or somewhere in between.
    // This is distinct from the screen power state, which is managed separately.
    //指示设备是醒着还是睡着，或者介于两者之间。
    //这与屏幕电源状态不同，后者是单独管理的。
    private int mWakefulness;

    // True if MSG_SANDMAN has been scheduled.
    private boolean mSandmanScheduled;

    // Table of all suspend blockers.
    // There should only be a few of these.
    private final ArrayList<SuspendBlocker> mSuspendBlockers = new ArrayList<SuspendBlocker>();

    // Table of all wake locks acquired by applications.
    private final ArrayList<WakeLock> mWakeLocks = new ArrayList<WakeLock>();

    // A bitfield that summarizes the state of all active wakelocks.
    private int mWakeLockSummary;

    // If true, instructs the display controller to wait for the proximity sensor to
    // go negative before turning the screen on.
    private boolean mRequestWaitForNegativeProximity;

    // Timestamp of the last time the device was awoken or put to sleep.
    private long mLastWakeTime;
    private long mLastSleepTime;

    // True if we need to send a wake up or go to sleep finished notification
    // when the display is ready.
    private boolean mSendWakeUpFinishedNotificationWhenReady;
    private boolean mSendGoToSleepFinishedNotificationWhenReady;

    // Timestamp of the last call to user activity.
    private long mLastUserActivityTime;
    private long mLastUserActivityTimeNoChangeLights;

    // A bitfield that summarizes the effect of the user activity timer.
    // A zero value indicates that the user activity timer has expired.
    private int mUserActivitySummary;

    // The desired display power state.  The actual state may lag behind the
    // requested because it is updated asynchronously by the display power controller.
    private final DisplayPowerRequest mDisplayPowerRequest = new DisplayPowerRequest();

    // The time the screen was last turned off, in elapsedRealtime() timebase.
    private long mLastScreenOffEventElapsedRealTime;

    // True if the display power state has been fully applied, which means the display
    // is actually on or actually off or whatever was requested.
    private boolean mDisplayReady;

    // The suspend blocker used to keep the CPU alive when an application has acquired
    // a wake lock.
    private final SuspendBlocker mWakeLockSuspendBlocker;

    // True if the wake lock suspend blocker has been acquired.
    private boolean mHoldingWakeLockSuspendBlocker;

    // The suspend blocker used to keep the CPU alive when the display is on, the
    // display is getting ready or there is user activity (in which case the display
    // must be on).
    private final SuspendBlocker mDisplaySuspendBlocker;

    // True if the display suspend blocker has been acquired.
    private boolean mHoldingDisplaySuspendBlocker;

    // The screen on blocker used to keep the screen from turning on while the lock
    // screen is coming up.
    private final ScreenOnBlockerImpl mScreenOnBlocker;

    // The display blanker used to turn the screen on or off.
    private final DisplayBlankerImpl mDisplayBlanker;

    // True if systemReady() has been called.
    private boolean mSystemReady;

    // True if boot completed occurred.  We keep the screen on until this happens.
    private boolean mBootCompleted;

    // True if the device is plugged into a power source.
    private boolean mIsPowered;

    // The current plug type, such as BatteryManager.BATTERY_PLUGGED_WIRELESS.
    private int mPlugType;

    // The current battery level percentage.
    private int mBatteryLevel;

    // The battery level percentage at the time the dream started.
    // This is used to terminate a dream and go to sleep if the battery is
    // draining faster than it is charging and the user activity timeout has expired.
    private int mBatteryLevelWhenDreamStarted;

    // The current dock state.
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    // True if the device should wake up when plugged or unplugged.
    private boolean mWakeUpWhenPluggedOrUnpluggedConfig;

    // True if the device should suspend when the screen is off due to proximity.
    private boolean mSuspendWhenScreenOffDueToProximityConfig;

    // True if dreams are supported on this device.
    private boolean mDreamsSupportedConfig;

    // Default value for dreams enabled
    private boolean mDreamsEnabledByDefaultConfig;

    // Default value for dreams activate-on-sleep
    private boolean mDreamsActivatedOnSleepByDefaultConfig;

    // Default value for dreams activate-on-dock
    private boolean mDreamsActivatedOnDockByDefaultConfig;

    // True if dreams are enabled by the user.
    private boolean mDreamsEnabledSetting;

    // True if dreams should be activated on sleep.
    private boolean mDreamsActivateOnSleepSetting;

    // True if dreams should be activated on dock.
    private boolean mDreamsActivateOnDockSetting;

    // The screen off timeout setting value in milliseconds.
    private int mScreenOffTimeoutSetting;

    // The maximum allowable screen off timeout according to the device
    // administration policy.  Overrides other settings.
    private int mMaximumScreenOffTimeoutFromDeviceAdmin = Integer.MAX_VALUE;

    // The stay on while plugged in setting.
    // A bitfield of battery conditions under which to make the screen stay on.
    private int mStayOnWhilePluggedInSetting;

    // True if the device should stay on.
    private boolean mStayOn;

    // True if the proximity sensor reads a positive result.
    private boolean mProximityPositive;

    // Screen brightness setting limits.
    //限定值,config.xml中配置
    private int mScreenBrightnessSettingMinimum;//0
    private int mScreenBrightnessSettingMaximum;//255
    private int mScreenBrightnessSettingDefault;//102

    // The screen brightness setting, from 0 to 255.
    // Use -1 if no value has been set.
    //Settings.System.SCREEN_BRIGHTNESS中的值，即反映给用户的值
    private int mScreenBrightnessSetting;

    // The screen auto-brightness adjustment setting, from -1 to 1.
    // Use 0 if there is no adjustment.
    //自动调节亮度调整值,-1～1
    private float mScreenAutoBrightnessAdjustmentSetting;

    // The screen brightness mode.
    // One of the Settings.System.SCREEN_BRIGHTNESS_MODE_* constants.
    private int mScreenBrightnessModeSetting;

    // The screen brightness setting override from the window manager
    // to allow the current foreground activity to override the brightness.
    // Use -1 to disable.
    //WindowManager覆盖的亮度值，如播放视频时调节亮度
	//-1表示禁止使用(未发现使用到)
    private int mScreenBrightnessOverrideFromWindowManager = -1;

    // The user activity timeout override from the window manager
    // to allow the current foreground activity to override the user activity timeout.
    // Use -1 to disable.
    private long mUserActivityTimeoutOverrideFromWindowManager = -1;

    // The screen brightness setting override from the settings application
    // to temporarily adjust the brightness until next updated,
    // Use -1 to disable.
    //SystemUI中设置的临时亮度值，自动亮度时无效
	//该值之所以是临时的，是因为当调节亮度进度条时，会调用到updateDisplayPowerLocked(),这里给它赋值；
	// 当手指放开时，调用updateSettingLocked(),这里又将它置为-1
    private int mTemporaryScreenBrightnessSettingOverride = -1;

    // The screen brightness adjustment setting override from the settings
    // application to temporarily adjust the auto-brightness adjustment factor
    // until next updated, in the range -1..1.
    // Use NaN to disable.
    private float mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = Float.NaN;

    // Time when we last logged a warning about calling userActivity() without permission.
    private long mLastWarningAboutUserActivityPermission = Long.MIN_VALUE;

    private native void nativeInit();

    private static native void nativeSetPowerState(boolean screenOn, boolean screenBright);
    private static native void nativeAcquireSuspendBlocker(String name);
    private static native void nativeReleaseSuspendBlocker(String name);
    private static native void nativeSetInteractive(boolean enable);
    private static native void nativeSetAutoSuspend(boolean enable);

    public PowerManagerService() {
        synchronized (mLock) {
            /*wwxx
                PowerManagerService 的构造方法首先创建了处理消息的线程和发送消息的 PowerManagerHandler 对象。
                接着创建了 mWakeLockSuspendBlocker 、 mDisplayBlanker 对象（后面会介绍它们的作用)。

                变量 mWakefulness 的值被设置成 WAKEFULNESS_AWAKE，它用来表示 PowerManagerService的状态,一共有4种定义。

                WAKEFULNESS_ASLEEP:表示系统目前处于休眠状态，只能被wakeUp()调用唤醒。
                WAKEFULNESS_AWAKE:表示系统目前处于正常运行状态。
                WAKEFULNESS_DREAMING:表示系统正处于播放屏保的状态。
                WAKEFULNESS_DOZING:表示系统正处于“doze”状态。这种状态下只有低耗电的“屏保”可以运行，其他应用进程都被挂起。

                最后，构造方法调用了nativeInit()方法，它在native层对应的函数如下。去看看
            */
            mWakeLockSuspendBlocker = createSuspendBlockerLocked("PowerManagerService.WakeLocks");
            mDisplaySuspendBlocker = createSuspendBlockerLocked("PowerManagerService.Display");
            mDisplaySuspendBlocker.acquire();
            mHoldingDisplaySuspendBlocker = true;

            mScreenOnBlocker = new ScreenOnBlockerImpl();
            mDisplayBlanker = new DisplayBlankerImpl();
            mWakefulness = WAKEFULNESS_AWAKE; // wwxx 设置PowerManagerService的状态
        }

        nativeInit();
        nativeSetPowerState(true, true);
    }

    /**
     * Initialize the power manager.
     * Must be called before any other functions within the power manager are called.
     */
    public void init(Context context, LightsService ls,
            ActivityManagerService am, BatteryService bs, IBatteryStats bss,
            IAppOpsService appOps, DisplayManagerService dm) {
        mContext = context;
        mLightsService = ls;
        mBatteryService = bs;
        mBatteryStats = bss;
        mAppOps = appOps;
        mDisplayManagerService = dm;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new PowerManagerHandler(mHandlerThread.getLooper());

        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler, mHandlerThread.getName());

        // Forcibly turn the screen on at boot so that it is in a known power state.
        // We do this in init() rather than in the constructor because setting the
        // screen state requires a call into surface flinger which then needs to call back
        // into the activity manager to check permissions.  Unfortunately the
        // activity manager is not running when the constructor is called, so we
        // have to defer setting the screen state until this point.
        mDisplayBlanker.unblankAllDisplays();
    }

    public void setPolicy(WindowManagerPolicy policy) {
        synchronized (mLock) {
            mPolicy = policy;
        }
    }
    /*wwxx
    SystemServer 创建 PowerManagerService 后，还会调用它的 SystemReady() 方法，相当于在系统准备就绪后对 PowerManagerService 再进行一些初始化工作。SystemReady()方法的代码如下。

    SystemReadyO方法完成的主要工作如下。
    获取缺省、最大、最小3种屏幕亮度。
    创建SystemSensorManager对象，用于和 SensorService交互。SensorService是一个native的Service，也存在于SystemServer进程中，它管理着Android上的各种传感设备。
    创建 Notifer 对象。Notifer 对象用于广播系统中和 power 相关的变化，例如，屏幕的关闭和打开等。
    创建 WirelessChargerDetector 对象，用于无线充电检测的传感器。
    调用 DisplayManagerService 的 initPowerManagement() 方法来初始化Power管理模块。注册Observer监听系统设置的变化。在Android的设置模块中，很多设置项都和
        PowerManagerService有关，包括屏幕的亮度、自动关闭屏幕的时间、能否启动“屏保”等。
    监听其他模块广播的 Intent。PowerManagerService需要关注系统的变化，这里注册了很多系统广播的接收器。包括系统启动完成、“屏保”启动和关闭、用户切换、Dock插拔等。
    */
    public void systemReady(TwilightService twilight, DreamManagerService dreamManager) {
        synchronized (mLock) {
            mSystemReady = true;
            mDreamManager = dreamManager;

            PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
            //获取缺省、最大、最小屏幕亮度
            mScreenBrightnessSettingMinimum = pm.getMinimumScreenBrightnessSetting();
            mScreenBrightnessSettingMaximum = pm.getMaximumScreenBrightnessSetting();
            mScreenBrightnessSettingDefault = pm.getDefaultScreenBrightnessSetting();
            //创建sensorManager对象，用于和 SensorService交互
            SensorManager sensorManager = new SystemSensorManager(mContext, mHandler.getLooper());

            // The notifier runs on the system server's main looper so as not to interfere
            // with the animations and other critical functions of the power manager.
            //创建Notifer对象
            mNotifier = new Notifier(Looper.getMainLooper(), mContext, mBatteryStats,
                    mAppOps, createSuspendBlockerLocked("PowerManagerService.Broadcasts"),
                    mScreenOnBlocker, mPolicy);

            // The display power controller runs on the power manager service's
            // own handler thread to ensure timely operation.
            mDisplayPowerController = new DisplayPowerController(mHandler.getLooper(),
                    mContext, mNotifier, mLightsService, twilight, sensorManager,
                    mDisplayManagerService, mDisplaySuspendBlocker, mDisplayBlanker,
                    mDisplayPowerControllerCallbacks, mHandler);

            mWirelessChargerDetector = new WirelessChargerDetector(sensorManager,
                    createSuspendBlockerLocked("PowerManagerService.WirelessChargerDetector"),
                    mHandler);
            //创建监听系统设置项变化的对象
            mSettingsObserver = new SettingsObserver(mHandler);
            mAttentionLight = mLightsService.getLight(LightsService.LIGHT_ID_ATTENTION);

            // Register for broadcasts from other components of the system.
            //监听其他模块广播的Intent
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            mContext.registerReceiver(new BatteryReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            mContext.registerReceiver(new BootCompletedReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DREAMING_STARTED);//屏保启动
            filter.addAction(Intent.ACTION_DREAMING_STOPPED);//屏保关闭
            mContext.registerReceiver(new DreamReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_SWITCHED);//用户切换
            mContext.registerReceiver(new UserSwitchedReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DOCK_EVENT);/lDock插拔事件
            mContext.registerReceiver(new DockReceiver(), filter, null, mHandler);

            // Register for settings changes.
            //监听设置的变化
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SCREENSAVER_ENABLED),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, mSettingsObserver, UserHandle.USER_ALL);

            // Go.
            readConfigurationLocked();
            updateSettingsLocked();
            mDirty |= DIRTY_BATTERY_STATE;
            updatePowerStateLocked();
        }
    }

    private void readConfigurationLocked() {
        final Resources resources = mContext.getResources();

        mWakeUpWhenPluggedOrUnpluggedConfig = resources.getBoolean(
                com.android.internal.R.bool.config_unplugTurnsOnScreen);
        mSuspendWhenScreenOffDueToProximityConfig = resources.getBoolean(
                com.android.internal.R.bool.config_suspendWhenScreenOffDueToProximity);
        mDreamsSupportedConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsSupported);
        mDreamsEnabledByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);
        mDreamsActivatedOnSleepByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault);
        mDreamsActivatedOnDockByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault);
    }

    private void updateSettingsLocked() {
        final ContentResolver resolver = mContext.getContentResolver();

        mDreamsEnabledSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ENABLED,
                mDreamsEnabledByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mDreamsActivateOnSleepSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                mDreamsActivatedOnSleepByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mDreamsActivateOnDockSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                mDreamsActivatedOnDockByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mScreenOffTimeoutSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_TIMEOUT,
                UserHandle.USER_CURRENT);
        mStayOnWhilePluggedInSetting = Settings.Global.getInt(resolver,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, BatteryManager.BATTERY_PLUGGED_AC);

        final int oldScreenBrightnessSetting = mScreenBrightnessSetting;
        mScreenBrightnessSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_BRIGHTNESS, mScreenBrightnessSettingDefault,
                UserHandle.USER_CURRENT);
        if (oldScreenBrightnessSetting != mScreenBrightnessSetting) {
            mTemporaryScreenBrightnessSettingOverride = -1;
        }

        final float oldScreenAutoBrightnessAdjustmentSetting =
                mScreenAutoBrightnessAdjustmentSetting;
        mScreenAutoBrightnessAdjustmentSetting = Settings.System.getFloatForUser(resolver,
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.0f,
                UserHandle.USER_CURRENT);
        if (oldScreenAutoBrightnessAdjustmentSetting != mScreenAutoBrightnessAdjustmentSetting) {
            mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = Float.NaN;
        }

        mScreenBrightnessModeSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);

        mDirty |= DIRTY_SETTINGS;
    }

    private void handleSettingsChangedLocked() {
        updateSettingsLocked();
        updatePowerStateLocked();
    }

    @Override // Binder call
    public void acquireWakeLockWithUid(IBinder lock, int flags, String tag, String packageName,
            int uid) {
        acquireWakeLock(lock, flags, tag, packageName, new WorkSource(uid));
    }

    @Override // Binder call
    public void acquireWakeLock(IBinder lock, int flags, String tag, String packageName,
            WorkSource ws) {
        if (lock == null) {
            throw new IllegalArgumentException("lock must not be null");
        }
        if (packageName == null) {
            throw new IllegalArgumentException("packageName must not be null");
        }
		////检查wakelock级别
        PowerManager.validateWakeLockParameters(flags, tag);
		//检查WAKE_LOCK权限
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (ws != null && ws.size() != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.UPDATE_DEVICE_STATS, null);
        } else {
            ws = null;
        }

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
		//重置当前线程上传入的IPC标志
        final long ident = Binder.clearCallingIdentity();
        try {
            acquireWakeLockInternal(lock, flags, tag, packageName, ws, uid, pid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void acquireWakeLockInternal(IBinder lock, int flags, String tag, String packageName,
            WorkSource ws, int uid, int pid) {
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "acquireWakeLockInternal: lock=" + Objects.hashCode(lock)
                        + ", flags=0x" + Integer.toHexString(flags)
                        + ", tag=\"" + tag + "\", ws=" + ws + ", uid=" + uid + ", pid=" + pid);
            }
			//PMS中的WakeLock类
            WakeLock wakeLock;
			 //查找是否已存在该PM.WakeLock实例
            int index = findWakeLockIndexLocked(lock);//检查这个lock是否已经存在
			 //是否存在wakelock
            if (index >= 0) {//lock已经存在
                wakeLock = mWakeLocks.get(index);
                if (!wakeLock.hasSameProperties(flags, tag, ws, uid, pid)) {
                    // Update existing wake lock.  This shouldn't happen but is harmless.
                    //更新wakelock
                    notifyWakeLockReleasedLocked(wakeLock);
                    wakeLock.updateProperties(flags, tag, packageName, ws, uid, pid);
                    notifyWakeLockAcquiredLocked(wakeLock);
                }
            } else {
            	//创建新的PMS.WakeLock实例
                wakeLock = new WakeLock(lock, flags, tag, packageName, ws, uid, pid);
                try {
                    lock.linkToDeath(wakeLock, 0);
                } catch (RemoteException ex) {
                    throw new IllegalArgumentException("Wake lock is already dead.");
                }
				 //表示有新的wakelock申请了,   
				 //当申请了锁后，在该方法中进行长时锁的判断，通知BatteryStatsService      
                 // 进行统计持锁时间等
                notifyWakeLockAcquiredLocked(wakeLock);
				//添加到wakelock集合中
                mWakeLocks.add(wakeLock);
            }
			//判断是否直接点亮屏幕，如果带有点亮屏幕标志值，并且wakelock类型为
			//FULL_WAKE_LOCK,SCREEN_BRIGHT_WAKE_LOCK,SCREEN_DIM_WAKE_LOCK,则进行下 
			//步处理
            applyWakeLockFlagsOnAcquireLocked(wakeLock);
			 //更新标志位
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }
    /*
    wwxx

    acquireWakeLockInternal()方法的主要工作是创建WakeLock对象并加入到 mWakeLocks 列表中，这个列表中包含了系统中所有 WakeLock 对象。

    但是如果 mWakeLocks 列表中已经存在具有相同 token 的 WakeLock 对象,则只更新其属性值,不会再创建对象，这个token是用户进程调用gotoSleep()接口时的参数:用户进程中的 WakeLock对象。

    创建或更新 WakeLcok 对象后，接下来调用 applyWakeLockFlagsOnAcquireLocked()方法，这个方法只是调用了 wakeUpNoUpdateLocked()方法，wakeUpNoUpdateLocked()方法的代码如下。去看看

    */

    @SuppressWarnings("deprecation")
    private static boolean isScreenLock(final WakeLock wakeLock) {
        switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
            case PowerManager.FULL_WAKE_LOCK:
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                return true;
        }
        return false;
    }

    private void applyWakeLockFlagsOnAcquireLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0
                && isScreenLock(wakeLock)) {
            wakeUpNoUpdateLocked(SystemClock.uptimeMillis());
        }
    }
/*wwxx
下面再看看PowerManagerService的releaseWakeLock()接口，这个接口也只是调用了PMS内部方法releaseWakeLockInternal()，代码如下。


*/
    @Override // Binder call
    public void releaseWakeLock(IBinder lock, int flags) {
        if (lock == null) {
            throw new IllegalArgumentException("lock must not be null");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            releaseWakeLockInternal(lock, flags);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
/*wwxx
releaseWakeLockInternal()方法的实现很简单，首先查找 lock 在 mWakeLocks 中的 index，然后从 mWakeLocks 中得到 WakeLock 对象，最后调用 removeWakeLockLocked() 方法释放锁，方法代码如下。

removeWakeLockLocked() 方法首先从 mWakeLocks 中移除 WakeLock 对象并发出通知，接着调用 WakeLockFlagsOnReleaseLocked() 方法，4.4不是这个方法,

最后 mDirty 的值被设置成 DIRTY_WAKE_LOCKS ，然后调用 updatePowerStateLocked 方法。

*/
    private void releaseWakeLockInternal(IBinder lock, int flags) {
        synchronized (mLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "releaseWakeLockInternal: lock=" + Objects.hashCode(lock)
                            + " [not found], flags=0x" + Integer.toHexString(flags));
                }
                return;
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            if (DEBUG_SPEW) {
                Slog.d(TAG, "releaseWakeLockInternal: lock=" + Objects.hashCode(lock)
                        + " [" + wakeLock.mTag + "], flags=0x" + Integer.toHexString(flags));
            }

            mWakeLocks.remove(index);
            notifyWakeLockReleasedLocked(wakeLock);
            wakeLock.mLock.unlinkToDeath(wakeLock, 0);

            if ((flags & PowerManager.WAIT_FOR_PROXIMITY_NEGATIVE) != 0) {
                mRequestWaitForNegativeProximity = true;
            }

            applyWakeLockFlagsOnReleaseLocked(wakeLock);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    private void handleWakeLockDeath(WakeLock wakeLock) {
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleWakeLockDeath: lock=" + Objects.hashCode(wakeLock.mLock)
                        + " [" + wakeLock.mTag + "]");
            }

            int index = mWakeLocks.indexOf(wakeLock);
            if (index < 0) {
                return;
            }

            mWakeLocks.remove(index);
            notifyWakeLockReleasedLocked(wakeLock);

            applyWakeLockFlagsOnReleaseLocked(wakeLock);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    private void applyWakeLockFlagsOnReleaseLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.ON_AFTER_RELEASE) != 0
                && isScreenLock(wakeLock)) {
            userActivityNoUpdateLocked(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS,
                    wakeLock.mOwnerUid);
        }
    }

    @Override // Binder call
    public void updateWakeLockUids(IBinder lock, int[] uids) {
        WorkSource ws = null;

        if (uids != null) {
            ws = new WorkSource();
            // XXX should WorkSource have a way to set uids as an int[] instead of adding them
            // one at a time?
            for (int i = 0; i < uids.length; i++) {
                ws.add(uids[i]);
            }
        }
        updateWakeLockWorkSource(lock, ws);
    }

    @Override // Binder call
    public void updateWakeLockWorkSource(IBinder lock, WorkSource ws) {
        if (lock == null) {
            throw new IllegalArgumentException("lock must not be null");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (ws != null && ws.size() != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.UPDATE_DEVICE_STATS, null);
        } else {
            ws = null;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            updateWakeLockWorkSourceInternal(lock, ws);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void updateWakeLockWorkSourceInternal(IBinder lock, WorkSource ws) {
        synchronized (mLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateWakeLockWorkSourceInternal: lock=" + Objects.hashCode(lock)
                            + " [not found], ws=" + ws);
                }
                throw new IllegalArgumentException("Wake lock not active");
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockWorkSourceInternal: lock=" + Objects.hashCode(lock)
                        + " [" + wakeLock.mTag + "], ws=" + ws);
            }

            if (!wakeLock.hasSameWorkSource(ws)) {
                notifyWakeLockReleasedLocked(wakeLock);
                wakeLock.updateWorkSource(ws);
                notifyWakeLockAcquiredLocked(wakeLock);
            }
        }
    }

    private int findWakeLockIndexLocked(IBinder lock) {
        final int count = mWakeLocks.size();
        for (int i = 0; i < count; i++) {
            if (mWakeLocks.get(i).mLock == lock) {
                return i;
            }
        }
        return -1;
    }

    private void notifyWakeLockAcquiredLocked(WakeLock wakeLock) {
        if (mSystemReady) {
            wakeLock.mNotifiedAcquired = true;
            mNotifier.onWakeLockAcquired(wakeLock.mFlags, wakeLock.mTag, wakeLock.mPackageName,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource);
        }
    }

    private void notifyWakeLockReleasedLocked(WakeLock wakeLock) {
        if (mSystemReady && wakeLock.mNotifiedAcquired) {
            wakeLock.mNotifiedAcquired = false;
            mNotifier.onWakeLockReleased(wakeLock.mFlags, wakeLock.mTag, wakeLock.mPackageName,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource);
        }
    }

    @Override // Binder call
    public boolean isWakeLockLevelSupported(int level) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return isWakeLockLevelSupportedInternal(level);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isWakeLockLevelSupportedInternal(int level) {
        synchronized (mLock) {
            switch (level) {
                case PowerManager.PARTIAL_WAKE_LOCK:
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                case PowerManager.FULL_WAKE_LOCK:
                    return true;

                case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                    return mSystemReady && mDisplayPowerController.isProximitySensorAvailable();

                default:
                    return false;
            }
        }
    }
/*wwxx
报告用户活动--userActivity 接口

PowerManger 是 PowerManagerService 的代理类，它提供了一些接口让用户进程可以和PowerManagerService交互，下面我们通过分析这些接口的实现来更进一步了解PMS的工作。

接口 userActivity() 用于用户进程向 PowerManagerService 报告用户影响系统休眠的活动，
例如，用户点击屏幕时，系统会调用该方法来告诉 PowerManagerService 用户点击的时间，这样PowerManagerService将更新内部保存的时间值，从而推迟系统休眠的时间。

userActivity() 方法主要通过调用内部的 userActivityInternal() 方法来完成工作，方法的代码如 本文件的实现处。
*/
    @Override // Binder call
    public void userActivity(long eventTime, int event, int flags) {
        final long now = SystemClock.uptimeMillis();
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                != PackageManager.PERMISSION_GRANTED) {
            // Once upon a time applications could call userActivity().
            // Now we require the DEVICE_POWER permission.  Log a warning and ignore the
            // request instead of throwing a SecurityException so we don't break old apps.
            synchronized (mLock) {
                if (now >= mLastWarningAboutUserActivityPermission + (5 * 60 * 1000)) {
                    mLastWarningAboutUserActivityPermission = now;
                    Slog.w(TAG, "Ignoring call to PowerManager.userActivity() because the "
                            + "caller does not have DEVICE_POWER permission.  "
                            + "Please fix your app!  "
                            + " pid=" + Binder.getCallingPid()
                            + " uid=" + Binder.getCallingUid());
                }
            }
            return;
        }

        if (eventTime > SystemClock.uptimeMillis()) {
            throw new IllegalArgumentException("event time must not be in the future");
        }

        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            userActivityInternal(eventTime, event, flags, uid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Called from native code.
    private void userActivityFromNative(long eventTime, int event, int flags) {
        userActivityInternal(eventTime, event, flags, Process.SYSTEM_UID);
    }
/*wwxx 
userActivityInternal() 先调用了 userActivityNoUpdateLocked() 方法，然后再调用 updatePowerStateLocked()方法。
userActivityNoUpdateLocked()方法只是把参数保存到内部变量中，并不会采取任何动作，而 PowerManagerService中核心的方法是updatePowerStateLocked()。

先去看看userActivityNoUpdateLocked 函数的实现，
*/
    private void userActivityInternal(long eventTime, int event, int flags, int uid) {
        synchronized (mLock) {
            if (userActivityNoUpdateLocked(eventTime, event, flags, uid)) {
                updatePowerStateLocked();
            }
        }
    }
/*wwxx
userActivityNoUpdateLocked() 方法主要的工作是更新几个内部变量。其中 mLastUserActivityTime 变量和 mLastUserActivityTimeNoChangeLights 变量用来记录调用userActivity()方法的时间, 
mDirty 用来记录用户的操作类型，这些变量的值在updatePowerStateLocked()方法中将会作为是否要执行睡眠或唤醒操作的依据。
*/
    private boolean userActivityNoUpdateLocked(long eventTime, int event, int flags, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "userActivityNoUpdateLocked: eventTime=" + eventTime
                    + ", event=" + event + ", flags=0x" + Integer.toHexString(flags)
                    + ", uid=" + uid);
        }

        if (eventTime < mLastSleepTime || eventTime < mLastWakeTime
                || mWakefulness == WAKEFULNESS_ASLEEP || !mBootCompleted || !mSystemReady) {
            return false;
        }

        mNotifier.onUserActivity(event, uid);//发出通知

        if ((flags & PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS) != 0) {
            if (eventTime > mLastUserActivityTimeNoChangeLights
                    && eventTime > mLastUserActivityTime) {
                mLastUserActivityTimeNoChangeLights = eventTime;
                mDirty |= DIRTY_USER_ACTIVITY;
                return true;
            }
        } else {
            if (eventTime > mLastUserActivityTime) {
                mLastUserActivityTime = eventTime;
                mDirty |= DIRTY_USER_ACTIVITY;
                return true;
            }
        }
        return false;
    }

    @Override // Binder call
    public void wakeUp(long eventTime) {
        if (eventTime > SystemClock.uptimeMillis()) {
            throw new IllegalArgumentException("event time must not be in the future");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            wakeUpInternal(eventTime);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Called from native code.
    private void wakeUpFromNative(long eventTime) {
        wakeUpInternal(eventTime);
    }

    private void wakeUpInternal(long eventTime) {
        synchronized (mLock) {
            if (wakeUpNoUpdateLocked(eventTime)) {
                updatePowerStateLocked();
            }
        }
    }
/*wwxx

wakeUpNoUpdateLocked() 方法首先调用 mNotifier 变量的 onWakeUpStarted() 函数，然后修改成员变量 mLastWakeTime、 mWakefulness 和 mDirty 的值。
 
最后 wakeUpNoUpdateLocked() 方法还调用了 userActivityNoUpdateLocked()，这个方法前面介绍 userActivity 接口时已经介绍过，这里调用它相当于又更新了变量 mLastUserActivityTime 的值。
*/
    private boolean wakeUpNoUpdateLocked(long eventTime) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "wakeUpNoUpdateLocked: eventTime=" + eventTime);
        }

        if (eventTime < mLastSleepTime || mWakefulness == WAKEFULNESS_AWAKE
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        switch (mWakefulness) {
            case WAKEFULNESS_ASLEEP:
                Slog.i(TAG, "Waking up from sleep...");
                sendPendingNotificationsLocked();
                mNotifier.onWakeUpStarted();
                mSendWakeUpFinishedNotificationWhenReady = true;
                break;
            case WAKEFULNESS_DREAMING:
                Slog.i(TAG, "Waking up from dream...");
                break;
            case WAKEFULNESS_NAPPING:
                Slog.i(TAG, "Waking up from nap...");
                break;
        }

        mLastWakeTime = eventTime;
        mWakefulness = WAKEFULNESS_AWAKE;
        mDirty |= DIRTY_WAKEFULNESS;

        userActivityNoUpdateLocked(
                eventTime, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
        return true;
    }
/*wwxx
强制系统进入休眠模式——gotoSleep接口

gotoSleep()用来强制系统进入休眠模式。通常当系统一段时间无人操作后，系统将调用gotoSleep()接口来进入休眠模式。
PowerManagerService 的 gotoSleep()接口主要是调用内部方法goToSleepInternal()来完成其功能,方法的代码见实现处。


*/
    @Override // Binder call
    public void goToSleep(long eventTime, int reason) {
        if (eventTime > SystemClock.uptimeMillis()) {
            throw new IllegalArgumentException("event time must not be in the future");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            goToSleepInternal(eventTime, reason);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Called from native code.
    private void goToSleepFromNative(long eventTime, int reason) {
        goToSleepInternal(eventTime, reason);
    }
/*wwxx
goToSleepInternal()代码的结构和前面的 userActivity 类似，都是先调用另一个内部方法，然后再调用 updatePowerStateLocked() 方法，我们先看看 goToSleepNoUpdateLocked()  方法，代码如下。


*/
    private void goToSleepInternal(long eventTime, int reason) {
        synchronized (mLock) {
            if (goToSleepNoUpdateLocked(eventTime, reason)) {
                updatePowerStateLocked();
            }
        }
    }
/*wwxx
goToSleepNoUpdateLocked()也非常简单，只是发送了将要休眠的通知，然后修改了成员变量mDirty、mLastSleepTime和 mWakefulness的值。
更多实际的工作还是在updatePowerStateLocked()方法中完成的。下面先了解WakeLock机制,然后再分析这个方法。

控制系统的休眠机制
Android 设备的休眠和唤醒主要基于WakeLock机制。WakeLock是一种上锁机制，只要有进程获得了WakeLock锁系统就不会进入休眠。
例如，在下载文件或播放歌曲时，即使休眠时间到了，系统也不能进行休眠。WakeLock可以设置超时，超时到后会自动解锁。

应用使用 WakeLock 功能前，需要先使用 newWakeLock() 接口创建一个 WakeLock 类的对象，
然后通过它的 acquire() 方法禁止系统休眠，应用完成工作后应该调用 release() 方法来恢复休眠机制，否则系统将无法休眠,直到耗光所有电量。

WakeLock 类中实现 acquire()和 release()方法.实际上是先调用了 PowerManagerService 的 acquireWakeLock()和 releaseWakeLock()方法。 这两个方法处都有笔记。


*/
    @SuppressWarnings("deprecation")
    private boolean goToSleepNoUpdateLocked(long eventTime, int reason) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "goToSleepNoUpdateLocked: eventTime=" + eventTime + ", reason=" + reason);
        }

        if (eventTime < mLastWakeTime || mWakefulness == WAKEFULNESS_ASLEEP
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        switch (reason) {
            case PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN:
                Slog.i(TAG, "Going to sleep due to device administration policy...");
                break;
            case PowerManager.GO_TO_SLEEP_REASON_TIMEOUT:
                Slog.i(TAG, "Going to sleep due to screen timeout...");
                break;
            default:
                Slog.i(TAG, "Going to sleep by user request...");
                reason = PowerManager.GO_TO_SLEEP_REASON_USER;
                break;
        }

        sendPendingNotificationsLocked();
        mNotifier.onGoToSleepStarted(reason);
        mSendGoToSleepFinishedNotificationWhenReady = true;

        mLastSleepTime = eventTime;
        mDirty |= DIRTY_WAKEFULNESS;
        mWakefulness = WAKEFULNESS_ASLEEP;

        // Report the number of wake locks that will be cleared by going to sleep.
        int numWakeLocksCleared = 0;
        final int numWakeLocks = mWakeLocks.size();
        for (int i = 0; i < numWakeLocks; i++) {
            final WakeLock wakeLock = mWakeLocks.get(i);
            switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                case PowerManager.FULL_WAKE_LOCK:
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    numWakeLocksCleared += 1;
                    break;
            }
        }
        EventLog.writeEvent(EventLogTags.POWER_SLEEP_REQUESTED, numWakeLocksCleared);
        return true;
    }

    @Override // Binder call
    public void nap(long eventTime) {
        if (eventTime > SystemClock.uptimeMillis()) {
            throw new IllegalArgumentException("event time must not be in the future");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            napInternal(eventTime);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void napInternal(long eventTime) {
        synchronized (mLock) {
            if (napNoUpdateLocked(eventTime)) {
                updatePowerStateLocked();
            }
        }
    }
    /*wwxx
    如果if语句中4项表达式有一项为true，这整个方法会返回false。但是，如果是循环中第一次调用该方法，则4项正常情况下都为false，其实前面已经进行过类似的判断了，
    如果成立就不会调用到这里。这样执行的结果就是改变了 mDirty 和 mWakefulness 的值，既然 mWakefulness 的值发生了改变， 那么循环中第二次调用本方法时， 肯定会返回false，
    这样就结束了 updatePowerStateLocked() 方法中的循环。
    */
    private boolean napNoUpdateLocked(long eventTime) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "napNoUpdateLocked: eventTime=" + eventTime);
        }

        if (eventTime < mLastWakeTime || mWakefulness != WAKEFULNESS_AWAKE
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Slog.i(TAG, "Nap time...");

        mDirty |= DIRTY_WAKEFULNESS;
        mWakefulness = WAKEFULNESS_NAPPING;
        return true;
    }

    /**
     * Updates the global power state based on dirty bits recorded in mDirty.
     *
     * This is the main function that performs power state transitions.
     * We centralize them here so that we can recompute the power state completely
     * each time something important changes, and ensure that we do it the same
     * way each time.  The point is to gather all of the transition logic here.
     */

    /*wwxx
    理解updatePowerStateLocked方法 

    updatePowerStateLocked()方法是 PowerManagerService 的核心，前面分析的接口调用都只是在更新服务中的某些成员变量的值，最后都需要调用 updatePowerStateLocked() 方法。方法的代码如下:

    */
    private void updatePowerStateLocked() {
        if (!mSystemReady || mDirty == 0) {
            return;
        }
        if (!Thread.holdsLock(mLock)) {
            Slog.wtf(TAG, "Power manager lock was not held when calling updatePowerStateLocked");
        }

        // Phase 0: Basic state updates.
        //更新基本状态
        updateIsPoweredLocked(mDirty);//更新 mIsPowered, mPlugType , mBatteryLevel
        updateStayOnLocked(mDirty);//更新 mStayon

        // Phase 1: Update wakefulness.
        // Loop because the wake lock and user activity computations are influenced
        // by changes in wakefulness.

        //更新 wakefulness.
        final long now = SystemClock.uptimeMillis();
        int dirtyPhase2 = 0;
        for (;;) {
            int dirtyPhase1 = mDirty;
            dirtyPhase2 |= dirtyPhase1;
            mDirty = 0;

            updateWakeLockSummaryLocked(dirtyPhase1);
            updateUserActivitySummaryLocked(now, dirtyPhase1);
            if (!updateWakefulnessLocked(dirtyPhase1)) {
                break;
            }
        }

        // Phase 2: Update dreams and display power state.
        //更新屏保状态
        updateDreamLocked(dirtyPhase2);//该方法用来更新设备Dream状态，比如是否继续屏保、Doze或者开始休眠，这个方法中异步处理该过程(因此，这里在唤醒或休眠时有风险)
		//和Display交互，请求Display状态
        updateDisplayPowerStateLocked(dirtyPhase2);//更新显示设备的状态

        // Phase 3: Send notifications, if needed.
        if (mDisplayReady) {//发送通知
            sendPendingNotificationsLocked();
        }

        // Phase 4: Update suspend blocker.
        // Because we might release the last suspend blocker here, we need to make sure
        // we finished everything else first!
        //在分析这个方法前，先来了解下什么是Suspend锁。
        /*Suspend锁机制是Android电源管理框架中的一种机制，
        在前面还提到的wakelock锁也是，不过wakelock锁是上层向framwork层申请，
        而Suspend锁是framework层中对wakelock锁的表现，也就是说，
        上层应用申请了wakelock锁后，在PMS中最终都会表现为Suspend锁，
        通过Suspend锁向Hal层写入节点，Kernal层会读取节点，从而进入唤醒或者休眠。
        这个方法就是用来申请Suspend锁操作，因此，该方法在分析wakelock锁申请流程时进行分析，
        此处暂且不进行分析*/
        updateSuspendBlockerLocked();//更新Suspend锁
    }/*wwxx
        updatePowerStateLocked()方法并不长，但是不太容易理解，下面仔细解释。

        (1) updatePowerStateLocked() 方法首先调用 updateIsPoweredLocked() 方法，这个方法主要通过调用 BatteryService 的接口来更新几个成员变量的值，如下所示;
            其中， mIsPowered 表示是否在充电、 mPlugType 表示充电的类型、 mBatteryLevel  表示当前电池电量的等级。

        (2）调用 updateStayOnLocked() 函数来更新变量 mStayOn 的值，mStayOn 如果为true， 屏幕将长亮不灭。
            在 Setting 中可以设置充电时屏幕长亮，如果 Setting 中设置了该选项， updateStayOnLocked() 函数中如果检测到正在充电，会将 mStayOn 的值设为true。
        (3）接下来是一个无限for 循环，注意，不要被这个无限循环给吓住了，以为它会循环很多次，其实最多两次它就结束了，这一点后面会分析。
            我们先看看循环中调用的 updateWakeLockSummaryLocked() 方法。这个方法的主要作用是根据 PowerManagerService 中所有 WakeLock 对象的类型，
            计算一个最终的类型集合，并保存在变量 mWakeLockSummary 中。不管系统中一共创建了多少个 WakeLock 对象，一个就足以阻止系统休眠，
            因此，这里把所有 WakeLock 对象的状态总结后放到一个变量中。应用创建 WakeLock 对象时，会指定对象的类型，这个类型将作为参数传递到 PowerManagerService 中。WakeLock 的类型有:
            PARTIAL_WAKE_LOCK:只保持CPU运行，屏幕背光和键盘背光关闭。
            FULL_WAKE_LOCK:CPU，屏幕背光和键盘背光都不关闭。
            SCREEN BRIGHT_WAKE_LOCK:屏幕背光不关闭，但是键盘背光关闭。
            SCREEN_DIM_WAKE_LOCK:屏幕背光不关闭，键盘背光关闭。但是屏幕背光可以变暗。
            ROXIMITY_SCREEN_OFF_WAKE_LOCK:这个类型并不是用来阻止系统进入休眠，而是用来打开距离传感器控制屏幕开关的功能。
                                          如果应用持有这种类型的 WakeLock，当距离传感器被遮挡时，屏幕将被关闭。在打电话时经常使用这个功能。
            DOZE_WAKE_LOCK:这个类型用来让屏保管理器实现 doze 模式。

        (4）循环中调用的第二个方法是 updateUserActivitySummaryLocked()，这个方法根据最后一次调用 userActivity() 方法的时间，
            计算现在是否可以将表示屏幕状态的变量 mUserActivitySummary 的值设置为 SCREEN_STATE_DIM(变暗)，或者 SCREEN_STATE_OFF(关闭)。
            如果时间还没到， 则发送一个定时消息 MSG_USER_ACTIVITY_TIMEOUT 。当处理消息的时间到了以后，会在消息的处理方法 handleUserActivityTimeout() 中重新调用
            updatePowerStateLocked()方法，如下所示:

                private void handleUserActivityTimeout() { // runs on handler thread
                    synchronized (mLock) {
                        if (DEBUG_SPEW) {
                            Slog.d(TAG, "handleUserActivityTimeout");
                        }

                        mDirty |= DIRTY_USER_ACTIVITY;
                        updatePowerStateLocked();
                    }
                }
            再次调用updatePowerStateLocked()方法时，会根据当前状态重新计算mUserActivitySummary的值。

        (5）循环中调用的第三个方法是 updateWakefulnessLocked()，这个方法是结束循环的关键。如果它的返回值是true，表示 PowerManagerService 的状态发生了变化，将继续循环，
            然后重新调用前面的两个方法 updateWakeLockSummaryLocked() 和 updateUserActivitySummaryLocked()来更新。
            而第二次调用 updateWakefulnessLocked() 通常都会返回false，这样就跳出了循环。我们看看这个方法的实现,在本文件实现处。

        (6）结束循环后， updatePowerStateLocked() 方法中又调用了 updateDisplayPowerStateLocked() 方法。
            这个方法的主要作用是根据更新后的 mUserActivitySummary 的值来确定屏幕的状态和亮度，并设置到 DisplayPowerController 对象中。
        (7）接下来调用 updateDreamLocked() 方法，如果条件合适，这个方法中将启动屏保。
        (8）最后调用 updateSuspendBlockerLocked() 方法，我们先看看它的代码:(见定义处)


    */

    private void sendPendingNotificationsLocked() {
        if (mSendWakeUpFinishedNotificationWhenReady) {
            mSendWakeUpFinishedNotificationWhenReady = false;
            mNotifier.onWakeUpFinished();
        }
        if (mSendGoToSleepFinishedNotificationWhenReady) {
            mSendGoToSleepFinishedNotificationWhenReady = false;
            mNotifier.onGoToSleepFinished();
        }
    }

    /**
     * Updates the value of mIsPowered.
     * Sets DIRTY_IS_POWERED if a change occurred.
     */
    private void updateIsPoweredLocked(int dirty) {
        if ((dirty & DIRTY_BATTERY_STATE) != 0) {
            final boolean wasPowered = mIsPowered;
            final int oldPlugType = mPlugType;
            mIsPowered = mBatteryService.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
            mPlugType = mBatteryService.getPlugType();
            mBatteryLevel = mBatteryService.getBatteryLevel();

            if (DEBUG) {
                Slog.d(TAG, "updateIsPoweredLocked: wasPowered=" + wasPowered
                        + ", mIsPowered=" + mIsPowered
                        + ", oldPlugType=" + oldPlugType
                        + ", mPlugType=" + mPlugType
                        + ", mBatteryLevel=" + mBatteryLevel);
            }

            if (wasPowered != mIsPowered || oldPlugType != mPlugType) {
                mDirty |= DIRTY_IS_POWERED;

                // Update wireless dock detection state.
                final boolean dockedOnWirelessCharger = mWirelessChargerDetector.update(
                        mIsPowered, mPlugType, mBatteryLevel);

                // Treat plugging and unplugging the devices as a user activity.
                // Users find it disconcerting when they plug or unplug the device
                // and it shuts off right away.
                // Some devices also wake the device when plugged or unplugged because
                // they don't have a charging LED.
                final long now = SystemClock.uptimeMillis();
                if (shouldWakeUpWhenPluggedOrUnpluggedLocked(wasPowered, oldPlugType,
                        dockedOnWirelessCharger)) {
                    wakeUpNoUpdateLocked(now);
                }
                userActivityNoUpdateLocked(
                        now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);

                // Tell the notifier whether wireless charging has started so that
                // it can provide feedback to the user.
                if (dockedOnWirelessCharger) {
                    mNotifier.onWirelessChargingStarted();
                }
            }
        }
    }

    private boolean shouldWakeUpWhenPluggedOrUnpluggedLocked(
            boolean wasPowered, int oldPlugType, boolean dockedOnWirelessCharger) {
        // Don't wake when powered unless configured to do so.
        if (!mWakeUpWhenPluggedOrUnpluggedConfig) {
            return false;
        }

        // Don't wake when undocked from wireless charger.
        // See WirelessChargerDetector for justification.
        if (wasPowered && !mIsPowered
                && oldPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            return false;
        }

        // Don't wake when docked on wireless charger unless we are certain of it.
        // See WirelessChargerDetector for justification.
        if (!wasPowered && mIsPowered
                && mPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS
                && !dockedOnWirelessCharger) {
            return false;
        }

        // If already dreaming and becoming powered, then don't wake.
        if (mIsPowered && (mWakefulness == WAKEFULNESS_NAPPING
                || mWakefulness == WAKEFULNESS_DREAMING)) {
            return false;
        }

        // Otherwise wake up!
        return true;
    }

    /**
     * Updates the value of mStayOn.
     * Sets DIRTY_STAY_ON if a change occurred.
     */
    private void updateStayOnLocked(int dirty) {
        if ((dirty & (DIRTY_BATTERY_STATE | DIRTY_SETTINGS)) != 0) {
            final boolean wasStayOn = mStayOn;
            if (mStayOnWhilePluggedInSetting != 0
                    && !isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()) {
                mStayOn = mBatteryService.isPowered(mStayOnWhilePluggedInSetting);
            } else {
                mStayOn = false;
            }

            if (mStayOn != wasStayOn) {
                mDirty |= DIRTY_STAY_ON;
            }
        }
    }

    /**
     * Updates the value of mWakeLockSummary to summarize the state of all active wake locks.
     * Note that most wake-locks are ignored when the system is asleep.
     *
     * This function must have no other side-effects.
     */
    @SuppressWarnings("deprecation")
    private void updateWakeLockSummaryLocked(int dirty) {
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_WAKEFULNESS)) != 0) {
            mWakeLockSummary = 0;

            final int numWakeLocks = mWakeLocks.size();
            for (int i = 0; i < numWakeLocks; i++) {
                final WakeLock wakeLock = mWakeLocks.get(i);
                switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                    case PowerManager.PARTIAL_WAKE_LOCK:
                        mWakeLockSummary |= WAKE_LOCK_CPU;
                        break;
                    case PowerManager.FULL_WAKE_LOCK:
                        if (mWakefulness != WAKEFULNESS_ASLEEP) {
                            mWakeLockSummary |= WAKE_LOCK_CPU
                                    | WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_BUTTON_BRIGHT;
                            if (mWakefulness == WAKEFULNESS_AWAKE) {
                                mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                            }
                        }
                        break;
                    case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                        if (mWakefulness != WAKEFULNESS_ASLEEP) {
                            mWakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_SCREEN_BRIGHT;
                            if (mWakefulness == WAKEFULNESS_AWAKE) {
                                mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                            }
                        }
                        break;
                    case PowerManager.SCREEN_DIM_WAKE_LOCK:
                        if (mWakefulness != WAKEFULNESS_ASLEEP) {
                            mWakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_SCREEN_DIM;
                            if (mWakefulness == WAKEFULNESS_AWAKE) {
                                mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                            }
                        }
                        break;
                    case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                        if (mWakefulness != WAKEFULNESS_ASLEEP) {
                            mWakeLockSummary |= WAKE_LOCK_PROXIMITY_SCREEN_OFF;
                        }
                        break;
                }
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockSummaryLocked: mWakefulness="
                        + wakefulnessToString(mWakefulness)
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            }
        }
    }

    /**
     * Updates the value of mUserActivitySummary to summarize the user requested
     * state of the system such as whether the screen should be bright or dim.
     * Note that user activity is ignored when the system is asleep.
     *
     * This function must have no other side-effects.
     */
    private void updateUserActivitySummaryLocked(long now, int dirty) {
        // Update the status of the user activity timeout timer.
        if ((dirty & (DIRTY_USER_ACTIVITY | DIRTY_WAKEFULNESS | DIRTY_SETTINGS)) != 0) {
            mHandler.removeMessages(MSG_USER_ACTIVITY_TIMEOUT);

            long nextTimeout = 0;
		//如果处于休眠状态，则不会执行该方法
            if (mWakefulness != WAKEFULNESS_ASLEEP) {
				//设备完全进入休眠所需时间，该值为-1表示禁用此值，默认-1 ,
				//用户超时时间，既经过一段时间不活动进入休眠或屏保的时间，特殊情况外，
				// 该值为Settings中的休眠时长
                final int screenOffTimeout = getScreenOffTimeoutLocked();
				//Dim时长，即亮屏不操作，变暗多久休眠
                final int screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);

                mUserActivitySummary = 0;
				//1.亮屏；2.亮屏后进行用户活动
                if (mLastUserActivityTime >= mLastWakeTime) {
					 //下次睡眠时间=上次用户活动时间+休眠时间-Dim时间
                    nextTimeout = mLastUserActivityTime
                            + screenOffTimeout - screenDimDuration;
					 //如果满足当前时间 < 下次屏幕超时时间，说明此时设备为亮屏状态，
					 //则将用户活动状态置为表示亮屏的USER_ACTIVITY_SCREEN_BRIGHT
                    if (now < nextTimeout) {
                        mUserActivitySummary |= USER_ACTIVITY_SCREEN_BRIGHT;
                    } else {
                    	//如果当前时间>下次活动时间，此时应有两种情况：已经休眠和Dim
                        nextTimeout = mLastUserActivityTime + screenOffTimeout;
						 //如果当前时间<上次活动时间+屏幕超时时间，这个值约为3s,
						 //说明此时设备为Dim状态，则将用户活动状态置为表示Dim的USER_ACTIVITY_SCREEN_DIM
                        if (now < nextTimeout) {
                            mUserActivitySummary |= USER_ACTIVITY_SCREEN_DIM;
                        }
                    }
                }
                if (mUserActivitySummary == 0
                        && mLastUserActivityTimeNoChangeLights >= mLastWakeTime) {
                    nextTimeout = mLastUserActivityTimeNoChangeLights + screenOffTimeout;
                    if (now < nextTimeout
                            && mDisplayPowerRequest.screenState
                                    != DisplayPowerRequest.SCREEN_STATE_OFF) {
                        mUserActivitySummary = mDisplayPowerRequest.screenState
                                == DisplayPowerRequest.SCREEN_STATE_BRIGHT ?
                                USER_ACTIVITY_SCREEN_BRIGHT : USER_ACTIVITY_SCREEN_DIM;
                    }
                }
				//发送定时Handler，到达时间后再次进行updatePowerStateLocked()
                if (mUserActivitySummary != 0) {
                    Message msg = mHandler.obtainMessage(MSG_USER_ACTIVITY_TIMEOUT);
                    msg.setAsynchronous(true);
                    mHandler.sendMessageAtTime(msg, nextTimeout);
                }
            } else {
                mUserActivitySummary = 0;
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateUserActivitySummaryLocked: mWakefulness="
                        + wakefulnessToString(mWakefulness)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary)
                        + ", nextTimeout=" + TimeUtils.formatUptime(nextTimeout));
            }
        }
    }

    /**
     * Called when a user activity timeout has occurred.
     * Simply indicates that something about user activity has changed so that the new
     * state can be recomputed when the power state is updated.
     *
     * This function must have no other side-effects besides setting the dirty
     * bit and calling update power state.  Wakefulness transitions are handled elsewhere.
     */
    private void handleUserActivityTimeout() { // runs on handler thread
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleUserActivityTimeout");
            }

            mDirty |= DIRTY_USER_ACTIVITY;
            updatePowerStateLocked();
        }
    }

    private int getScreenOffTimeoutLocked() {
        int timeout = mScreenOffTimeoutSetting;
        if (isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()) {
            timeout = Math.min(timeout, mMaximumScreenOffTimeoutFromDeviceAdmin);
        }
        if (mUserActivityTimeoutOverrideFromWindowManager >= 0) {
            timeout = (int)Math.min(timeout, mUserActivityTimeoutOverrideFromWindowManager);
        }
        return Math.max(timeout, MINIMUM_SCREEN_OFF_TIMEOUT);
    }

    private int getScreenDimDurationLocked(int screenOffTimeout) {
        return Math.min(SCREEN_DIM_DURATION,
                (int)(screenOffTimeout * MAXIMUM_SCREEN_DIM_RATIO));
    }

    /**
     * Updates the wakefulness of the device.
     *
     * This is the function that decides whether the device should start napping
     * based on the current wake locks and user activity state.  It may modify mDirty
     * if the wakefulness changes.
     *
     * Returns true if the wakefulness changed and we need to restart power state calculation.
     */

    /*wwxx
    updateWakefulnessLocked()方法中首先判断 dirty 的值，如果是第一次调用，这个条件很容易就满足了，注意，第二个if语句的判断条件，它要求 mWakefulness 的值为 WAKEFULNESS_AWAKE,
    并且调用方法 isItBedTimeYetLocked()的返回值为true才继续执行，否则方法结束并返回 false，当然返回false就会跳出循环了。
    我们先假定调用的时候 mWakefulness 等于 WAKEFULNESS_AWAKE(大部分情况的确如此)，下面看看方法 isItBedTimeYetLocked() 什么情况下返回true，代码如下所示:
    private boolean isItBedTimeYetLocked() {
        return mBootCompleted && !isBeingKeptAwakeLocked();
    }

    isItBedTimeYetLocked() 方法判断了两个条件，第一个条件 mBootCompleted 表示启动是否完成(启动没完成前是不能睡眠的)，这个变量启动后就是 true了。
    第二个条件是 isBeingKeptAwakeLocked() 方法的返回值，如果系统目前不能睡眠，这个方法将返回true.

    isBeingKeptAwakeLocked()方法判断系统是否能睡眠的几个变量前面都讲过了,前面讲到的一些方法中更新这些变量就是为了用在这里。

    因此， isItBedTimeYetLocked()方法只有在系统能够进入睡眠的情况下才返回true。

    让我们回到 updateWakefulnessLocked()方法中，假如系统能够睡眠，接下来将调用方法 shouldNapAtBedTimeLocked()，这个方法将检查系统有没有设置睡眠时间到启动屏保或者插在Dock上启动屏保。
    如果设置了，将调用 napNoUpdateLocked() 方法，没有设置则调用goToSleepNoUpdateLocked()方法。

    在 napNoUpdateLocked()方法中，见 napNoUpdateLocked 方法处的笔记。

    而 goToSlcepNoUpdateLocked() 方法的作用是通过设置变量 mWakefulness ，将系统的状态转换为 WAKEFULNESS_ASLEEP， 具体的代码就不分析了。

    */
    private boolean updateWakefulnessLocked(int dirty) {
        boolean changed = false;
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_BOOT_COMPLETED
                | DIRTY_WAKEFULNESS | DIRTY_STAY_ON | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_DOCK_STATE)) != 0) {
                 //当前屏幕保持唤醒&&设备将要退出唤醒状态(睡眠or屏保)
            if (mWakefulness == WAKEFULNESS_AWAKE && isItBedTimeYetLocked()) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateWakefulnessLocked: Bed time...");
                }
                final long time = SystemClock.uptimeMillis();
				//是否在休眠时启用屏保
                if (shouldNapAtBedTimeLocked()) {
					//进入屏保，返回true
                    changed = napNoUpdateLocked(time);
                } else {
                	//进入睡眠，返回true
                    changed = goToSleepNoUpdateLocked(time,
                            PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
                }
            }
        }
        return changed;
    }

    /**
     * Returns true if the device should automatically nap and start dreaming when the user
     * activity timeout has expired and it's bedtime.
     */
    private boolean shouldNapAtBedTimeLocked() {
        return mDreamsActivateOnSleepSetting//屏保是否开启
        		//插入基座时是否开启屏保
                || (mDreamsActivateOnDockSetting
                        && mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED);
    }

    /**
     * Returns true if the device should go to sleep now.
     * Also used when exiting a dream to determine whether we should go back
     * to being fully awake or else go to sleep for good.
     */
    private boolean isItBedTimeYetLocked() {
        return mBootCompleted && !isBeingKeptAwakeLocked();
    }

    /**
     * Returns true if the device is being kept awake by a wake lock, user activity
     * or the stay on while powered setting.  We also keep the phone awake when
     * the proximity sensor returns a positive result so that the device does not
     * lock while in a phone call.  This function only controls whether the device
     * will go to sleep or dream which is independent of whether it will be allowed
     * to suspend.
     */
    private boolean isBeingKeptAwakeLocked() {
        return mStayOn //屏幕是否保持常亮
                || mProximityPositive //接近传感器接近屏幕时为true
                || (mWakeLockSummary & WAKE_LOCK_STAY_AWAKE) != 0 //处于awake状态
                //屏幕处于亮屏或者dim状态
                || (mUserActivitySummary & (USER_ACTIVITY_SCREEN_BRIGHT
                        | USER_ACTIVITY_SCREEN_DIM)) != 0;
    }

    /**
     * Determines whether to post a message to the sandman to update the dream state.
     */
    private void updateDreamLocked(int dirty) {
        if ((dirty & (DIRTY_WAKEFULNESS
                | DIRTY_USER_ACTIVITY
                | DIRTY_WAKE_LOCKS
                | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS
                | DIRTY_IS_POWERED
                | DIRTY_STAY_ON
                | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_BATTERY_STATE)) != 0) {
            scheduleSandmanLocked();
        }
    }

    private void scheduleSandmanLocked() {
        if (!mSandmanScheduled) {
			//由于是异步处理，因此表示是否已经调用该方法且没有被handler处理
        	//，如果为true就不会进入该方法了
            mSandmanScheduled = true;
            Message msg = mHandler.obtainMessage(MSG_SANDMAN);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Called when the device enters or exits a napping or dreaming state.
     *
     * We do this asynchronously because we must call out of the power manager to start
     * the dream and we don't want to hold our lock while doing so.  There is a risk that
     * the device will wake or go to sleep in the meantime so we have to handle that case.
     */
    private void handleSandman() { // runs on handler thread
        // Handle preconditions.
        //是否开始进入屏保
        boolean startDreaming = false;
        synchronized (mLock) {
			//为false后下次updateDreamLocked()可处理
            mSandmanScheduled = false;
			//当前状态能否进入Dream
            boolean canDream = canDreamLocked();
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleSandman: canDream=" + canDream
                        + ", mWakefulness=" + wakefulnessToString(mWakefulness));
            }

            if (canDream && mWakefulness == WAKEFULNESS_NAPPING) {
                startDreaming = true;
            }
        }
		//表示是否正在屏保
        // Start dreaming if needed.
        // We only control the dream on the handler thread, so we don't need to worry about
        // concurrent attempts to start or stop the dream.
        boolean isDreaming = false;
        if (mDreamManager != null) {
			//重启屏保
            if (startDreaming) {
                mDreamManager.startDream();
            }
            isDreaming = mDreamManager.isDreaming();
        }

        // Update dream state.
        // We might need to stop the dream again if the preconditions changed.
        boolean continueDreaming = false;
        synchronized (mLock) {
            if (isDreaming && canDreamLocked()) {
                if (mWakefulness == WAKEFULNESS_NAPPING) {
                    mWakefulness = WAKEFULNESS_DREAMING;
                    mDirty |= DIRTY_WAKEFULNESS;
                    mBatteryLevelWhenDreamStarted = mBatteryLevel;
                    updatePowerStateLocked();
                    continueDreaming = true;
                } else if (mWakefulness == WAKEFULNESS_DREAMING) {
                    if (!isBeingKeptAwakeLocked()
                            && mBatteryLevel < mBatteryLevelWhenDreamStarted
                                    - DREAM_BATTERY_LEVEL_DRAIN_CUTOFF) {
                        // If the user activity timeout expired and the battery appears
                        // to be draining faster than it is charging then stop dreaming
                        // and go to sleep.
                        Slog.i(TAG, "Stopping dream because the battery appears to "
                                + "be draining faster than it is charging.  "
                                + "Battery level when dream started: "
                                + mBatteryLevelWhenDreamStarted + "%.  "
                                + "Battery level now: " + mBatteryLevel + "%.");
                    } else {
                        continueDreaming = true;
                    }
                }
            }
            if (!continueDreaming) {
                handleDreamFinishedLocked();
            }
        }

        // Stop dreaming if needed.
        // It's possible that something else changed to make us need to start the dream again.
        // If so, then the power manager will have posted another message to the handler
        // to take care of it later.
        if (mDreamManager != null) {
            if (!continueDreaming) {
                mDreamManager.stopDream();
            }
        }
    }

    /**
     * Returns true if the device is allowed to dream in its current state
     * assuming that it is currently napping or dreaming.
     */
    private boolean canDreamLocked() {
        return mDreamsSupportedConfig
                && mDreamsEnabledSetting
                && mDisplayPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF
                && mBootCompleted
                && (mIsPowered || isBeingKeptAwakeLocked());
    }

    /**
     * Called when a dream is ending to figure out what to do next.
     */
    private void handleDreamFinishedLocked() {
        if (mWakefulness == WAKEFULNESS_NAPPING
                || mWakefulness == WAKEFULNESS_DREAMING) {
            if (isItBedTimeYetLocked()) {
                goToSleepNoUpdateLocked(SystemClock.uptimeMillis(),
                        PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
                updatePowerStateLocked();
            } else {
                wakeUpNoUpdateLocked(SystemClock.uptimeMillis());
                updatePowerStateLocked();
            }
        }
    }

    private void handleScreenOnBlockerReleased() {
        synchronized (mLock) {
            mDirty |= DIRTY_SCREEN_ON_BLOCKER_RELEASED;
            updatePowerStateLocked();
        }
    }

    /**
     * Updates the display power state asynchronously.
     * When the update is finished, mDisplayReady will be set to true.  The display
     * controller posts a message to tell us when the actual display power state
     * has been updated so we come back here to double-check and finish up.
     *
     * This function recalculates the display power state each time.
     */
    private void updateDisplayPowerStateLocked(int dirty) {
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_WAKEFULNESS
                | DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS | DIRTY_SCREEN_ON_BLOCKER_RELEASED)) != 0) {
            int newScreenState = getDesiredScreenPowerStateLocked();
            if (newScreenState != mDisplayPowerRequest.screenState) {
                if (newScreenState == DisplayPowerRequest.SCREEN_STATE_OFF
                        && mDisplayPowerRequest.screenState
                                != DisplayPowerRequest.SCREEN_STATE_OFF) {
                    mLastScreenOffEventElapsedRealTime = SystemClock.elapsedRealtime();
                }

                mDisplayPowerRequest.screenState = newScreenState;
                nativeSetPowerState(
                        newScreenState != DisplayPowerRequest.SCREEN_STATE_OFF,
                        newScreenState == DisplayPowerRequest.SCREEN_STATE_BRIGHT);
            }

            int screenBrightness = mScreenBrightnessSettingDefault;
            float screenAutoBrightnessAdjustment = 0.0f;
            boolean autoBrightness = (mScreenBrightnessModeSetting ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            if (isValidBrightness(mScreenBrightnessOverrideFromWindowManager)) {
                screenBrightness = mScreenBrightnessOverrideFromWindowManager;
                autoBrightness = false;
            } else if (isValidBrightness(mTemporaryScreenBrightnessSettingOverride)) {
                screenBrightness = mTemporaryScreenBrightnessSettingOverride;
            } else if (isValidBrightness(mScreenBrightnessSetting)) {
                screenBrightness = mScreenBrightnessSetting;
            }
            if (autoBrightness) {
                screenBrightness = mScreenBrightnessSettingDefault;
                if (isValidAutoBrightnessAdjustment(
                        mTemporaryScreenAutoBrightnessAdjustmentSettingOverride)) {
                    screenAutoBrightnessAdjustment =
                            mTemporaryScreenAutoBrightnessAdjustmentSettingOverride;
                } else if (isValidAutoBrightnessAdjustment(
                        mScreenAutoBrightnessAdjustmentSetting)) {
                    screenAutoBrightnessAdjustment = mScreenAutoBrightnessAdjustmentSetting;
                }
            }
            screenBrightness = Math.max(Math.min(screenBrightness,
                    mScreenBrightnessSettingMaximum), mScreenBrightnessSettingMinimum);
            screenAutoBrightnessAdjustment = Math.max(Math.min(
                    screenAutoBrightnessAdjustment, 1.0f), -1.0f);
			// 封装到DisplayPowerRequest中
            mDisplayPowerRequest.screenBrightness = screenBrightness;
            mDisplayPowerRequest.screenAutoBrightnessAdjustment =
                    screenAutoBrightnessAdjustment;
            mDisplayPowerRequest.useAutoBrightness = autoBrightness;

            mDisplayPowerRequest.useProximitySensor = shouldUseProximitySensorLocked();

            mDisplayPowerRequest.blockScreenOn = mScreenOnBlocker.isHeld();
			// 传给DisplayManagerService中处理
            mDisplayReady = mDisplayPowerController.requestPowerState(mDisplayPowerRequest,
                    mRequestWaitForNegativeProximity);
            mRequestWaitForNegativeProximity = false;

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateScreenStateLocked: mDisplayReady=" + mDisplayReady
                        + ", newScreenState=" + newScreenState
                        + ", mWakefulness=" + mWakefulness
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary)
                        + ", mBootCompleted=" + mBootCompleted);
            }
        }
    }

    private static boolean isValidBrightness(int value) {
        return value >= 0 && value <= 255;
    }

    private static boolean isValidAutoBrightnessAdjustment(float value) {
        // Handles NaN by always returning false.
        return value >= -1.0f && value <= 1.0f;
    }

    private int getDesiredScreenPowerStateLocked() {
        if (mWakefulness == WAKEFULNESS_ASLEEP) {
            return DisplayPowerRequest.SCREEN_STATE_OFF;
        }

        if ((mWakeLockSummary & WAKE_LOCK_SCREEN_BRIGHT) != 0
                || (mUserActivitySummary & USER_ACTIVITY_SCREEN_BRIGHT) != 0
                || !mBootCompleted) {
            return DisplayPowerRequest.SCREEN_STATE_BRIGHT;
        }

        return DisplayPowerRequest.SCREEN_STATE_DIM;
    }

    private final DisplayPowerController.Callbacks mDisplayPowerControllerCallbacks =
            new DisplayPowerController.Callbacks() {
        @Override
        public void onStateChanged() {
            synchronized (mLock) {
                mDirty |= DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED;
                updatePowerStateLocked();
            }
        }

        @Override
        public void onProximityPositive() {
            synchronized (mLock) {
                mProximityPositive = true;
                mDirty |= DIRTY_PROXIMITY_POSITIVE;
                updatePowerStateLocked();
            }
        }

        @Override
        public void onProximityNegative() {
            synchronized (mLock) {
                mProximityPositive = false;
                mDirty |= DIRTY_PROXIMITY_POSITIVE;
                userActivityNoUpdateLocked(SystemClock.uptimeMillis(),
                        PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
                updatePowerStateLocked();
            }
        }
    };

    private boolean shouldUseProximitySensorLocked() {
        return (mWakeLockSummary & WAKE_LOCK_PROXIMITY_SCREEN_OFF) != 0;
    }

    /**
     * Updates the suspend blocker that keeps the CPU alive.
     *
     * This function must have no other side-effects.
     */
    /*wwxx
    updateSuspendBlockerLocked()方法首先根据变量 mWakeLockSummary 是否带有 WAKE_LOCK_CPU 标志,以及 needDisplaySuspendBlocker() 方法的返回值决定
    是否需要阻止CPU休眠或保持屏幕长亮,然后分别调用 mWakeLockSuspendBlocker 和 mDisplaySuspendBlocker 的 acquire()和release()方法来完成CPU和屏幕的锁定和解锁操作。
    前面所进行的所有计算和更新操作，最后都汇集到这里来决定是休眠还是唤醒。
    最后再看看 needDisplaySuspendBlockerLocked () 方法如何决定屏幕是否关闭:

    */
    private void updateSuspendBlockerLocked() {
        final boolean needWakeLockSuspendBlocker = ((mWakeLockSummary & WAKE_LOCK_CPU) != 0);
        final boolean needDisplaySuspendBlocker = needDisplaySuspendBlocker();

        // First acquire suspend blockers if needed.
        if (needWakeLockSuspendBlocker && !mHoldingWakeLockSuspendBlocker) {// 如果不能休眠,调用底层的wake_lock函数
            mWakeLockSuspendBlocker.acquire();
            mHoldingWakeLockSuspendBlocker = true;
        }
        if (needDisplaySuspendBlocker && !mHoldingDisplaySuspendBlocker) {//如果需要保存屏幕长亮,调用底层的wake_lock函数
            mDisplaySuspendBlocker.acquire();
            mHoldingDisplaySuspendBlocker = true;
        }

        // Then release suspend blockers if needed.
        if (!needWakeLockSuspendBlocker && mHoldingWakeLockSuspendBlocker) {// 如果可以休眠,调用底层的解锁函数
            mWakeLockSuspendBlocker.release();
            mHoldingWakeLockSuspendBlocker = false;
        }
        if (!needDisplaySuspendBlocker && mHoldingDisplaySuspendBlocker) {//如果不需要保存屏幕长亮,调用底层的解锁函数
            mDisplaySuspendBlocker.release();
            mHoldingDisplaySuspendBlocker = false;
        }
    }

    /**
     * Return true if we must keep a suspend blocker active on behalf of the display.
     * We do so if the screen is on or is in transition between states.
     */
    /*wwxx

    needDisplaySuspendBlocker ()方法主要是根据屏幕的状态来决定,如果屏幕开着或处于变暗状态时，近距离传感器也没有工作,不能关闭屏幕。

    */
    private boolean needDisplaySuspendBlocker() {
        if (!mDisplayReady) {//如果显示设备没准备好,不能关闭屏幕
            return true;
        }
        if (mDisplayPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF) {//如果屏幕开着或处于变暗的状态，而且距离传感器没有工作，不能关闭屏幕
            // If we asked for the screen to be on but it is off due to the proximity
            // sensor then we may suspend but only if the configuration allows it.
            // On some hardware it may not be safe to suspend because the proximity
            // sensor may not be correctly configured as a wake-up source.
            if (!mDisplayPowerRequest.useProximitySensor || !mProximityPositive
                    || !mSuspendWhenScreenOffDueToProximityConfig) {
                return true;
            }
        }
        return false;//可以关闭屏幕
    }

    @Override // Binder call
    public boolean isScreenOn() {
        final long ident = Binder.clearCallingIdentity();
        try {
            return isScreenOnInternal();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isScreenOnInternal() {
        synchronized (mLock) {
            return !mSystemReady
                    || mDisplayPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF;
        }
    }

    private void handleBatteryStateChangedLocked() {
        mDirty |= DIRTY_BATTERY_STATE;
        updatePowerStateLocked();
    }

    private void startWatchingForBootAnimationFinished() {
        mHandler.sendEmptyMessage(MSG_CHECK_IF_BOOT_ANIMATION_FINISHED);
    }

    private void checkIfBootAnimationFinished() {
        if (DEBUG) {
            Slog.d(TAG, "Check if boot animation finished...");
        }

        if (SystemService.isRunning(BOOT_ANIMATION_SERVICE)) {
            mHandler.sendEmptyMessageDelayed(MSG_CHECK_IF_BOOT_ANIMATION_FINISHED,
                    BOOT_ANIMATION_POLL_INTERVAL);
            return;
        }

        synchronized (mLock) {
            if (!mBootCompleted) {
                Slog.i(TAG, "Boot animation finished.");
                handleBootCompletedLocked();
            }
        }
    }

    private void handleBootCompletedLocked() {
        final long now = SystemClock.uptimeMillis();
        mBootCompleted = true;
        mDirty |= DIRTY_BOOT_COMPLETED;
        userActivityNoUpdateLocked(
                now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
        updatePowerStateLocked();
    }

    /**
     * Reboots the device.
     *
     * @param confirm If true, shows a reboot confirmation dialog.
     * @param reason The reason for the reboot, or null if none.
     * @param wait If true, this call waits for the reboot to complete and does not return.
     */
    @Override // Binder call
    public void reboot(boolean confirm, String reason, boolean wait) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            shutdownOrRebootInternal(false, confirm, reason, wait);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Shuts down the device.
     *
     * @param confirm If true, shows a shutdown confirmation dialog.
     * @param wait If true, this call waits for the shutdown to complete and does not return.
     */
    @Override // Binder call
    public void shutdown(boolean confirm, boolean wait) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            shutdownOrRebootInternal(true, confirm, null, wait);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void shutdownOrRebootInternal(final boolean shutdown, final boolean confirm,
            final String reason, boolean wait) {
        if (mHandler == null || !mSystemReady) {
            throw new IllegalStateException("Too early to call shutdown() or reboot()");
        }

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                synchronized (this) {
                    if (shutdown) {
                        ShutdownThread.shutdown(mContext, confirm);
                    } else {
                        ShutdownThread.reboot(mContext, reason, confirm);
                    }
                }
            }
        };

        // ShutdownThread must run on a looper capable of displaying the UI.
        Message msg = Message.obtain(mHandler, runnable);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);

        // PowerManager.reboot() is documented not to return so just wait for the inevitable.
        if (wait) {
            synchronized (runnable) {
                while (true) {
                    try {
                        runnable.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }

    /**
     * Crash the runtime (causing a complete restart of the Android framework).
     * Requires REBOOT permission.  Mostly for testing.  Should not return.
     */
    @Override // Binder call
    public void crash(String message) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            crashInternal(message);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void crashInternal(final String message) {
        Thread t = new Thread("PowerManagerService.crash()") {
            @Override
            public void run() {
                throw new RuntimeException(message);
            }
        };
        try {
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Log.wtf(TAG, e);
        }
    }

    /**
     * Set the setting that determines whether the device stays on when plugged in.
     * The argument is a bit string, with each bit specifying a power source that,
     * when the device is connected to that source, causes the device to stay on.
     * See {@link android.os.BatteryManager} for the list of power sources that
     * can be specified. Current values include {@link android.os.BatteryManager#BATTERY_PLUGGED_AC}
     * and {@link android.os.BatteryManager#BATTERY_PLUGGED_USB}
     *
     * Used by "adb shell svc power stayon ..."
     *
     * @param val an {@code int} containing the bits that specify which power sources
     * should cause the device to stay on.
     */
    @Override // Binder call
    public void setStayOnSetting(int val) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SETTINGS, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setStayOnSettingInternal(val);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setStayOnSettingInternal(int val) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, val);
    }

    /**
     * Used by device administration to set the maximum screen off timeout.
     *
     * This method must only be called by the device administration policy manager.
     */
    @Override // Binder call
    public void setMaximumScreenOffTimeoutFromDeviceAdmin(int timeMs) {
        final long ident = Binder.clearCallingIdentity();
        try {
            setMaximumScreenOffTimeoutFromDeviceAdminInternal(timeMs);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setMaximumScreenOffTimeoutFromDeviceAdminInternal(int timeMs) {
        synchronized (mLock) {
            mMaximumScreenOffTimeoutFromDeviceAdmin = timeMs;
            mDirty |= DIRTY_SETTINGS;
            updatePowerStateLocked();
        }
    }

    private boolean isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() {
        return mMaximumScreenOffTimeoutFromDeviceAdmin >= 0
                && mMaximumScreenOffTimeoutFromDeviceAdmin < Integer.MAX_VALUE;
    }

    /**
     * Used by the phone application to make the attention LED flash when ringing.
     */
    @Override // Binder call
    public void setAttentionLight(boolean on, int color) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setAttentionLightInternal(on, color);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setAttentionLightInternal(boolean on, int color) {
        LightsService.Light light;
        synchronized (mLock) {
            if (!mSystemReady) {
                return;
            }
            light = mAttentionLight;
        }

        // Control light outside of lock.
        light.setFlashing(color, LightsService.LIGHT_FLASH_HARDWARE, (on ? 3 : 0), 0);
    }

    /**
     * Used by the Watchdog.
     */
    public long timeSinceScreenWasLastOn() {
        synchronized (mLock) {
            if (mDisplayPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF) {
                return 0;
            }
            return SystemClock.elapsedRealtime() - mLastScreenOffEventElapsedRealTime;
        }
    }

    /**
     * Used by the window manager to override the screen brightness based on the
     * current foreground activity.
     *
     * This method must only be called by the window manager.
     *
     * @param brightness The overridden brightness, or -1 to disable the override.
     */
    public void setScreenBrightnessOverrideFromWindowManager(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setScreenBrightnessOverrideFromWindowManagerInternal(brightness);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setScreenBrightnessOverrideFromWindowManagerInternal(int brightness) {
        synchronized (mLock) {
            if (mScreenBrightnessOverrideFromWindowManager != brightness) {
                mScreenBrightnessOverrideFromWindowManager = brightness;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    /**
     * Used by the window manager to override the button brightness based on the
     * current foreground activity.
     *
     * This method must only be called by the window manager.
     *
     * @param brightness The overridden brightness, or -1 to disable the override.
     */
    public void setButtonBrightnessOverrideFromWindowManager(int brightness) {
        // Do nothing.
        // Button lights are not currently supported in the new implementation.
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
    }

    /**
     * Used by the window manager to override the user activity timeout based on the
     * current foreground activity.  It can only be used to make the timeout shorter
     * than usual, not longer.
     *
     * This method must only be called by the window manager.
     *
     * @param timeoutMillis The overridden timeout, or -1 to disable the override.
     */
    public void setUserActivityTimeoutOverrideFromWindowManager(long timeoutMillis) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setUserActivityTimeoutOverrideFromWindowManagerInternal(timeoutMillis);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setUserActivityTimeoutOverrideFromWindowManagerInternal(long timeoutMillis) {
        synchronized (mLock) {
            if (mUserActivityTimeoutOverrideFromWindowManager != timeoutMillis) {
                mUserActivityTimeoutOverrideFromWindowManager = timeoutMillis;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    /**
     * Used by the settings application and brightness control widgets to
     * temporarily override the current screen brightness setting so that the
     * user can observe the effect of an intended settings change without applying
     * it immediately.
     *
     * The override will be canceled when the setting value is next updated.
     *
     * @param brightness The overridden brightness.
     *
     * @see android.provider.Settings.System#SCREEN_BRIGHTNESS
     */
    @Override // Binder call
    public void setTemporaryScreenBrightnessSettingOverride(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setTemporaryScreenBrightnessSettingOverrideInternal(brightness);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setTemporaryScreenBrightnessSettingOverrideInternal(int brightness) {
        synchronized (mLock) {
            if (mTemporaryScreenBrightnessSettingOverride != brightness) {
                mTemporaryScreenBrightnessSettingOverride = brightness;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    /**
     * Used by the settings application and brightness control widgets to
     * temporarily override the current screen auto-brightness adjustment setting so that the
     * user can observe the effect of an intended settings change without applying
     * it immediately.
     *
     * The override will be canceled when the setting value is next updated.
     *
     * @param adj The overridden brightness, or Float.NaN to disable the override.
     *
     * @see Settings.System#SCREEN_AUTO_BRIGHTNESS_ADJ
     */
    @Override // Binder call
    public void setTemporaryScreenAutoBrightnessAdjustmentSettingOverride(float adj) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setTemporaryScreenAutoBrightnessAdjustmentSettingOverrideInternal(adj);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setTemporaryScreenAutoBrightnessAdjustmentSettingOverrideInternal(float adj) {
        synchronized (mLock) {
            // Note: This condition handles NaN because NaN is not equal to any other
            // value, including itself.
            if (mTemporaryScreenAutoBrightnessAdjustmentSettingOverride != adj) {
                mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = adj;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    /**
     * Low-level function turn the device off immediately, without trying
     * to be clean.  Most people should use {@link ShutdownThread} for a clean shutdown.
     */
    public static void lowLevelShutdown() {
        SystemProperties.set("sys.powerctl", "shutdown");
    }

    /**
     * Low-level function to reboot the device. On success, this function
     * doesn't return. If more than 5 seconds passes from the time,
     * a reboot is requested, this method returns.
     *
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     */
    public static void lowLevelReboot(String reason) {
        if (reason == null) {
            reason = "";
        }
        SystemProperties.set("sys.powerctl", "reboot," + reason);
        try {
            Thread.sleep(20000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override // Watchdog.Monitor implementation
    public void monitor() {
        // Grab and release lock for watchdog monitor to detect deadlocks.
        synchronized (mLock) {
        }
    }

    @Override // Binder call
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump PowerManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("POWER MANAGER (dumpsys power)\n");

        final DisplayPowerController dpc;
        final WirelessChargerDetector wcd;
        synchronized (mLock) {
            pw.println("Power Manager State:");
            pw.println("  mDirty=0x" + Integer.toHexString(mDirty));
            pw.println("  mWakefulness=" + wakefulnessToString(mWakefulness));
            pw.println("  mIsPowered=" + mIsPowered);
            pw.println("  mPlugType=" + mPlugType);
            pw.println("  mBatteryLevel=" + mBatteryLevel);
            pw.println("  mBatteryLevelWhenDreamStarted=" + mBatteryLevelWhenDreamStarted);
            pw.println("  mDockState=" + mDockState);
            pw.println("  mStayOn=" + mStayOn);
            pw.println("  mProximityPositive=" + mProximityPositive);
            pw.println("  mBootCompleted=" + mBootCompleted);
            pw.println("  mSystemReady=" + mSystemReady);
            pw.println("  mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            pw.println("  mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary));
            pw.println("  mRequestWaitForNegativeProximity=" + mRequestWaitForNegativeProximity);
            pw.println("  mSandmanScheduled=" + mSandmanScheduled);
            pw.println("  mLastWakeTime=" + TimeUtils.formatUptime(mLastWakeTime));
            pw.println("  mLastSleepTime=" + TimeUtils.formatUptime(mLastSleepTime));
            pw.println("  mSendWakeUpFinishedNotificationWhenReady="
                    + mSendWakeUpFinishedNotificationWhenReady);
            pw.println("  mSendGoToSleepFinishedNotificationWhenReady="
                    + mSendGoToSleepFinishedNotificationWhenReady);
            pw.println("  mLastUserActivityTime=" + TimeUtils.formatUptime(mLastUserActivityTime));
            pw.println("  mLastUserActivityTimeNoChangeLights="
                    + TimeUtils.formatUptime(mLastUserActivityTimeNoChangeLights));
            pw.println("  mDisplayReady=" + mDisplayReady);
            pw.println("  mHoldingWakeLockSuspendBlocker=" + mHoldingWakeLockSuspendBlocker);
            pw.println("  mHoldingDisplaySuspendBlocker=" + mHoldingDisplaySuspendBlocker);

            pw.println();
            pw.println("Settings and Configuration:");
            pw.println("  mWakeUpWhenPluggedOrUnpluggedConfig="
                    + mWakeUpWhenPluggedOrUnpluggedConfig);
            pw.println("  mSuspendWhenScreenOffDueToProximityConfig="
                    + mSuspendWhenScreenOffDueToProximityConfig);
            pw.println("  mDreamsSupportedConfig=" + mDreamsSupportedConfig);
            pw.println("  mDreamsEnabledByDefaultConfig=" + mDreamsEnabledByDefaultConfig);
            pw.println("  mDreamsActivatedOnSleepByDefaultConfig="
                    + mDreamsActivatedOnSleepByDefaultConfig);
            pw.println("  mDreamsActivatedOnDockByDefaultConfig="
                    + mDreamsActivatedOnDockByDefaultConfig);
            pw.println("  mDreamsEnabledSetting=" + mDreamsEnabledSetting);
            pw.println("  mDreamsActivateOnSleepSetting=" + mDreamsActivateOnSleepSetting);
            pw.println("  mDreamsActivateOnDockSetting=" + mDreamsActivateOnDockSetting);
            pw.println("  mScreenOffTimeoutSetting=" + mScreenOffTimeoutSetting);
            pw.println("  mMaximumScreenOffTimeoutFromDeviceAdmin="
                    + mMaximumScreenOffTimeoutFromDeviceAdmin + " (enforced="
                    + isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() + ")");
            pw.println("  mStayOnWhilePluggedInSetting=" + mStayOnWhilePluggedInSetting);
            pw.println("  mScreenBrightnessSetting=" + mScreenBrightnessSetting);
            pw.println("  mScreenAutoBrightnessAdjustmentSetting="
                    + mScreenAutoBrightnessAdjustmentSetting);
            pw.println("  mScreenBrightnessModeSetting=" + mScreenBrightnessModeSetting);
            pw.println("  mScreenBrightnessOverrideFromWindowManager="
                    + mScreenBrightnessOverrideFromWindowManager);
            pw.println("  mUserActivityTimeoutOverrideFromWindowManager="
                    + mUserActivityTimeoutOverrideFromWindowManager);
            pw.println("  mTemporaryScreenBrightnessSettingOverride="
                    + mTemporaryScreenBrightnessSettingOverride);
            pw.println("  mTemporaryScreenAutoBrightnessAdjustmentSettingOverride="
                    + mTemporaryScreenAutoBrightnessAdjustmentSettingOverride);
            pw.println("  mScreenBrightnessSettingMinimum=" + mScreenBrightnessSettingMinimum);
            pw.println("  mScreenBrightnessSettingMaximum=" + mScreenBrightnessSettingMaximum);
            pw.println("  mScreenBrightnessSettingDefault=" + mScreenBrightnessSettingDefault);

            final int screenOffTimeout = getScreenOffTimeoutLocked();
            final int screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
            pw.println();
            pw.println("Screen off timeout: " + screenOffTimeout + " ms");
            pw.println("Screen dim duration: " + screenDimDuration + " ms");

            pw.println();
            pw.println("Wake Locks: size=" + mWakeLocks.size());
            for (WakeLock wl : mWakeLocks) {
                pw.println("  " + wl);
            }

            pw.println();
            pw.println("Suspend Blockers: size=" + mSuspendBlockers.size());
            for (SuspendBlocker sb : mSuspendBlockers) {
                pw.println("  " + sb);
            }

            pw.println();
            pw.println("Screen On Blocker: " + mScreenOnBlocker);

            pw.println();
            pw.println("Display Blanker: " + mDisplayBlanker);

            dpc = mDisplayPowerController;
            wcd = mWirelessChargerDetector;
        }

        if (dpc != null) {
            dpc.dump(pw);
        }

        if (wcd != null) {
            wcd.dump(pw);
        }
    }

    private SuspendBlocker createSuspendBlockerLocked(String name) {
        SuspendBlocker suspendBlocker = new SuspendBlockerImpl(name);
        mSuspendBlockers.add(suspendBlocker);
        return suspendBlocker;
    }

    private static String wakefulnessToString(int wakefulness) {
        switch (wakefulness) {
            case WAKEFULNESS_ASLEEP:
                return "Asleep";
            case WAKEFULNESS_AWAKE:
                return "Awake";
            case WAKEFULNESS_DREAMING:
                return "Dreaming";
            case WAKEFULNESS_NAPPING:
                return "Napping";
            default:
                return Integer.toString(wakefulness);
        }
    }

    private static WorkSource copyWorkSource(WorkSource workSource) {
        return workSource != null ? new WorkSource(workSource) : null;
    }

    private final class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                handleBatteryStateChangedLocked();
            }
        }
    }

    private final class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // This is our early signal that the system thinks it has finished booting.
            // However, the boot animation may still be running for a few more seconds
            // since it is ultimately in charge of when it terminates.
            // Defer transitioning into the boot completed state until the animation exits.
            // We do this so that the screen does not start to dim prematurely before
            // the user has actually had a chance to interact with the device.
            startWatchingForBootAnimationFinished();
        }
    }

    private final class DreamReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                scheduleSandmanLocked();
            }
        }
    }

    private final class UserSwitchedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                handleSettingsChangedLocked();
            }
        }
    }

    private final class DockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (mDockState != dockState) {
                    mDockState = dockState;
                    mDirty |= DIRTY_DOCK_STATE;
                    updatePowerStateLocked();
                }
            }
        }
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                handleSettingsChangedLocked();
            }
        }
    }

    /**
     * Handler for asynchronous operations performed by the power manager.
     */
    private final class PowerManagerHandler extends Handler {
        public PowerManagerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_ACTIVITY_TIMEOUT:
                    handleUserActivityTimeout();
                    break;
                case MSG_SANDMAN:
                    handleSandman();//当updateDreamLocked()方法调用后，最终会异步执行这个方法，
                                    //在这个方法中进行屏保相关处理，继续看看这个方法：
                    break;
                case MSG_SCREEN_ON_BLOCKER_RELEASED:
                    handleScreenOnBlockerReleased();
                    break;
                case MSG_CHECK_IF_BOOT_ANIMATION_FINISHED:
                    checkIfBootAnimationFinished();
                    break;
            }
        }
    }

    /**
     * Represents a wake lock that has been acquired by an application.
     */

/*wwxx
WakeLock的native层实现

我们先回到 PowerManagerService 的构造函数，看看它是如何创建两个变量 mWakeLockSuspendBlocker 和 mDisplaySuspendBlocker的。
            mWakeLockSuspendBlocker = createSuspendBlockerLocked("PowerManagerService.WakeLocks");
            mDisplaySuspendBlocker = createSuspendBlockerLocked("PowerManagerService.Display");

从代码中可以看出，这两个变量都是调用createSuspendBlockerLocked()方法来创建的，但是参数不一样，
一个是“PowerManagerService.WakeLocks ”，另一个是“PowerManagerService.Display”。方法的代码如下。

    private SuspendBlocker createSuspendBlockerLocked(String name) {
        SuspendBlocker suspendBlocker = new SuspendBlockerImpl(name);
        mSuspendBlockers.add(suspendBlocker);
        return suspendBlocker;
    }

createSuspendBlockerLocked()方法中创建了一个 SuspendBlockerImpl 对象并返回，
因此 mWakeLockSuspendBlocker 和 mDisplaySuspendBlocker 两个变量的实际类型应该是 SuspendBlockerImpl ，我们看看它的 acquire() 和 release() 方法的代码,去看看，就在下面。

*/    
    private final class WakeLock implements IBinder.DeathRecipient {
        public final IBinder mLock;
        public int mFlags;
        public String mTag;
        public final String mPackageName;
        public WorkSource mWorkSource;
        public final int mOwnerUid;
        public final int mOwnerPid;
        public boolean mNotifiedAcquired;

        public WakeLock(IBinder lock, int flags, String tag, String packageName,
                WorkSource workSource, int ownerUid, int ownerPid) {
            mLock = lock;
            mFlags = flags;
            mTag = tag;
            mPackageName = packageName;
            mWorkSource = copyWorkSource(workSource);
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
        }

        @Override
        public void binderDied() {
            PowerManagerService.this.handleWakeLockDeath(this);
        }

        public boolean hasSameProperties(int flags, String tag, WorkSource workSource,
                int ownerUid, int ownerPid) {
            return mFlags == flags
                    && mTag.equals(tag)
                    && hasSameWorkSource(workSource)
                    && mOwnerUid == ownerUid
                    && mOwnerPid == ownerPid;
        }

        public void updateProperties(int flags, String tag, String packageName,
                WorkSource workSource, int ownerUid, int ownerPid) {
            if (!mPackageName.equals(packageName)) {
                throw new IllegalStateException("Existing wake lock package name changed: "
                        + mPackageName + " to " + packageName);
            }
            if (mOwnerUid != ownerUid) {
                throw new IllegalStateException("Existing wake lock uid changed: "
                        + mOwnerUid + " to " + ownerUid);
            }
            if (mOwnerPid != ownerPid) {
                throw new IllegalStateException("Existing wake lock pid changed: "
                        + mOwnerPid + " to " + ownerPid);
            }
            mFlags = flags;
            mTag = tag;
            updateWorkSource(workSource);
        }

        public boolean hasSameWorkSource(WorkSource workSource) {
            return Objects.equal(mWorkSource, workSource);
        }

        public void updateWorkSource(WorkSource workSource) {
            mWorkSource = copyWorkSource(workSource);
        }

        @Override
        public String toString() {
            return getLockLevelString()
                    + " '" + mTag + "'" + getLockFlagsString()
                    + " (uid=" + mOwnerUid + ", pid=" + mOwnerPid + ", ws=" + mWorkSource + ")";
        }

        private String getLockLevelString() {
            switch (mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                case PowerManager.FULL_WAKE_LOCK:
                    return "FULL_WAKE_LOCK                ";
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                    return "SCREEN_BRIGHT_WAKE_LOCK       ";
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    return "SCREEN_DIM_WAKE_LOCK          ";
                case PowerManager.PARTIAL_WAKE_LOCK:
                    return "PARTIAL_WAKE_LOCK             ";
                case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                    return "PROXIMITY_SCREEN_OFF_WAKE_LOCK";
                default:
                    return "???                           ";
            }
        }

        private String getLockFlagsString() {
            String result = "";
            if ((mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                result += " ACQUIRE_CAUSES_WAKEUP";
            }
            if ((mFlags & PowerManager.ON_AFTER_RELEASE) != 0) {
                result += " ON_AFTER_RELEASE";
            }
            return result;
        }
    }
/*wwxx 
SuspendBlockerImpl 类中维护了一个计数器，调用 acquire() 方法时计数器加一，当计数器的值为1时，调用底层的 nativeAcquireSuspendBlocker 方法。
调用 release 方法时计数器减一，当计数器的值减为0时，调用 nativeReleaseSuspendBlocker 方法。这两个方法在 native 层对应的函数,去看看。

*/
    private final class SuspendBlockerImpl implements SuspendBlocker {
        private final String mName;
        private int mReferenceCount;

        public SuspendBlockerImpl(String name) {
            mName = name;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mReferenceCount != 0) {
                    Log.wtf(TAG, "Suspend blocker \"" + mName
                            + "\" was finalized without being released!");
                    mReferenceCount = 0;
                    nativeReleaseSuspendBlocker(mName);
                }
            } finally {
                super.finalize();
            }
        }

        @Override
        public void acquire() {
            synchronized (this) {
                mReferenceCount += 1;
                if (mReferenceCount == 1) {
                    if (DEBUG_SPEW) {
                        Slog.d(TAG, "Acquiring suspend blocker \"" + mName + "\".");
                    }
                    nativeAcquireSuspendBlocker(mName);
                }
            }
        }

        @Override
        public void release() {
            synchronized (this) {
                mReferenceCount -= 1;
                if (mReferenceCount == 0) {
                    if (DEBUG_SPEW) {
                        Slog.d(TAG, "Releasing suspend blocker \"" + mName + "\".");
                    }
                    nativeReleaseSuspendBlocker(mName);
                } else if (mReferenceCount < 0) {
                    Log.wtf(TAG, "Suspend blocker \"" + mName
                            + "\" was released without being acquired!", new Throwable());
                    mReferenceCount = 0;
                }
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return mName + ": ref count=" + mReferenceCount;
            }
        }
    }

    private final class ScreenOnBlockerImpl implements ScreenOnBlocker {
        private int mNestCount;

        public boolean isHeld() {
            synchronized (this) {
                return mNestCount != 0;
            }
        }

        @Override
        public void acquire() {
            synchronized (this) {
                mNestCount += 1;
                if (DEBUG) {
                    Slog.d(TAG, "Screen on blocked: mNestCount=" + mNestCount);
                }
            }
        }

        @Override
        public void release() {
            synchronized (this) {
                mNestCount -= 1;
                if (mNestCount < 0) {
                    Log.wtf(TAG, "Screen on blocker was released without being acquired!",
                            new Throwable());
                    mNestCount = 0;
                }
                if (mNestCount == 0) {
                    mHandler.sendEmptyMessage(MSG_SCREEN_ON_BLOCKER_RELEASED);
                }
                if (DEBUG) {
                    Slog.d(TAG, "Screen on unblocked: mNestCount=" + mNestCount);
                }
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return "held=" + (mNestCount != 0) + ", mNestCount=" + mNestCount;
            }
        }
    }

    private final class DisplayBlankerImpl implements DisplayBlanker {
        private boolean mBlanked;

        @Override
        public void blankAllDisplays() {
            synchronized (this) {
                mBlanked = true;
                mDisplayManagerService.blankAllDisplaysFromPowerManager();
                nativeSetInteractive(false);
                nativeSetAutoSuspend(true);
            }
        }

        @Override
        public void unblankAllDisplays() {
            synchronized (this) {
                nativeSetAutoSuspend(false);
                nativeSetInteractive(true);
                mDisplayManagerService.unblankAllDisplaysFromPowerManager();
                mBlanked = false;
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return "blanked=" + mBlanked;
            }
        }
    }
}
