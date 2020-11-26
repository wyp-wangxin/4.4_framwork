/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "PowerManagerService-JNI"

//#define LOG_NDEBUG 0

#include "JNIHelp.h"
#include "jni.h"

#include <ScopedUtfChars.h>

#include <limits.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <utils/Timers.h>
#include <utils/misc.h>
#include <utils/String8.h>
#include <utils/Log.h>
#include <hardware/power.h>
#include <hardware_legacy/power.h>
#include <suspend/autosuspend.h>

#include "com_android_server_power_PowerManagerService.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jmethodID wakeUpFromNative;
    jmethodID goToSleepFromNative;
    jmethodID userActivityFromNative;
} gPowerManagerServiceClassInfo;

// ----------------------------------------------------------------------------

static jobject gPowerManagerServiceObj;
static struct power_module* gPowerModule;

static Mutex gPowerManagerLock;
static bool gScreenOn;
static bool gScreenBright;

static nsecs_t gLastEventTime[USER_ACTIVITY_EVENT_LAST + 1];

// Throttling interval for user activity calls.
static const nsecs_t MIN_TIME_BETWEEN_USERACTIVITIES = 500 * 1000000L; // 500ms

// ----------------------------------------------------------------------------

static bool checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
        return true;
    }
    return false;
}

bool android_server_PowerManagerService_isScreenOn() {
    AutoMutex _l(gPowerManagerLock);
    return gScreenOn;
}

bool android_server_PowerManagerService_isScreenBright() {
    AutoMutex _l(gPowerManagerLock);
    return gScreenBright;
}

void android_server_PowerManagerService_userActivity(nsecs_t eventTime, int32_t eventType) {
    // Tell the power HAL when user activity occurs.
    if (gPowerModule && gPowerModule->powerHint) {
        gPowerModule->powerHint(gPowerModule, POWER_HINT_INTERACTION, NULL);
    }

    if (gPowerManagerServiceObj) {
        // Throttle calls into user activity by event type.
        // We're a little conservative about argument checking here in case the caller
        // passes in bad data which could corrupt system state.
        if (eventType >= 0 && eventType <= USER_ACTIVITY_EVENT_LAST) {
            nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
            if (eventTime > now) {
                eventTime = now;
            }

            if (gLastEventTime[eventType] + MIN_TIME_BETWEEN_USERACTIVITIES > eventTime) {
                return;
            }
            gLastEventTime[eventType] = eventTime;
        }

        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(gPowerManagerServiceObj,
                gPowerManagerServiceClassInfo.userActivityFromNative,
                nanoseconds_to_milliseconds(eventTime), eventType, 0);
        checkAndClearExceptionFromCallback(env, "userActivityFromNative");
    }
}

void android_server_PowerManagerService_wakeUp(nsecs_t eventTime) {
    if (gPowerManagerServiceObj) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(gPowerManagerServiceObj,
                gPowerManagerServiceClassInfo.wakeUpFromNative,
                nanoseconds_to_milliseconds(eventTime));
        checkAndClearExceptionFromCallback(env, "wakeUpFromNative");
    }
}

void android_server_PowerManagerService_goToSleep(nsecs_t eventTime) {
    if (gPowerManagerServiceObj) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(gPowerManagerServiceObj,
                gPowerManagerServiceClassInfo.goToSleepFromNative,
                nanoseconds_to_milliseconds(eventTime), 0);
        checkAndClearExceptionFromCallback(env, "goToSleepFromNative");
    }
}

// ----------------------------------------------------------------------------
/*wwxx
nativeInit() 函数的主要工作是装载“Power”模块，然后调用了模块的初始化函数 init()。
*/
static void nativeInit(JNIEnv* env, jobject obj) {
    gPowerManagerServiceObj = env->NewGlobalRef(obj);

    status_t err = hw_get_module(POWER_HARDWARE_MODULE_ID,
            (hw_module_t const**)&gPowerModule);
    if (!err) {
        gPowerModule->init(gPowerModule);
    } else {
        ALOGE("Couldn't load %s module (%s)", POWER_HARDWARE_MODULE_ID, strerror(-err));
    }
}

static void nativeSetPowerState(JNIEnv* env,
        jclass clazz, jboolean screenOn, jboolean screenBright) {
    AutoMutex _l(gPowerManagerLock);
    gScreenOn = screenOn;
    gScreenBright = screenBright;
}
/*wwxx
这两个native层的函数又分别调用了 acquire_wake_lock() 和 release_wake_lock() 函数来实现其功能,代码如下。

acquire_wake_lock 的实现在 /hardware/libhardware_legacy/power/power.c 文件里

int
acquire_wake_lock(int lock, const char* id)
{
    initialize_fds();
    if (g_error) return g_error;

    int fd;

    if (lock == PARTIAL_WAKE_LOCK) {//只支持PARTIAL_WAKE_LOCK
        fd = g_fds[ACQUIRE_PARTIAL_WAKE_LOCK];
    }
    else {
        return EINVAL;
    }
    return write(fd, id, strlen(id));
}


从power.c两个函数的实现可以看到，不管是 acquire_wake_lock() 函数还是 release_wake_lock()都是通过向不同的驱动文件中写数据来实现其功能的，
这里写的数据就是前面的构造方法中创建变量时传递的参数“PowerManagerService.WakeLocks”和“PowerManagerService.Display”。
那么acquire()和 release()中使用的文件设备句柄是如何创建的呢?我们看看 initialize_fds() 函数，(power.c里面)如下所示:

static inline void initialize_fds(void)
{
    // XXX: should be this:
    //pthread_once(&g_initialized, open_file_descriptors);
    // XXX: not this:
    if (g_initialized == 0) {
        if(open_file_descriptors(NEW_PATHS) < 0)
            open_file_descriptors(OLD_PATHS);
        g_initialized = 1;
    }
}

initialize_fds()函数先打开 NEW_PATHS 数组中的设备文件，不成功再打开 OLD_PATHS 数组中的设备文件，两个数组的定义如下:
const char * const OLD_PATHS[] = {
    "/sys/android_power/acquire_partial_wake_lock",
    "/sys/android_power/release_wake_lock",
};

const char * const NEW_PATHS[] = {
    "/sys/power/wake_lock",
    "/sys/power/wake_unlock",
};

因此，Android 实现防止系统休眠的功能是通过向设备文件“/sys/power/wake_lock” 中写数据来完成的，如果写的是“PowerManagerService.WakeLocks”，系统将不能进入休眠状态，

但是屏幕会关闭，

如果写的是“PowerManagerService.Display”字串，则连屏幕也不会关闭。

如果系统要恢复休眠，再向设备文件“/sys/power/wake_unlock”中写入同样的字符串就可以了。

*/ 
static void nativeAcquireSuspendBlocker(JNIEnv *env, jclass clazz, jstring nameStr) {
    ScopedUtfChars name(env, nameStr);
    acquire_wake_lock(PARTIAL_WAKE_LOCK, name.c_str());
}

static void nativeReleaseSuspendBlocker(JNIEnv *env, jclass clazz, jstring nameStr) {
    ScopedUtfChars name(env, nameStr);
    release_wake_lock(name.c_str());
}

static void nativeSetInteractive(JNIEnv *env, jclass clazz, jboolean enable) {
    if (gPowerModule) {
        if (enable) {
            ALOGD_IF_SLOW(20, "Excessive delay in setInteractive(true) while turning screen on");
            gPowerModule->setInteractive(gPowerModule, true);
        } else {
            ALOGD_IF_SLOW(20, "Excessive delay in setInteractive(false) while turning screen off");
            gPowerModule->setInteractive(gPowerModule, false);
        }
    }
}

static void nativeSetAutoSuspend(JNIEnv *env, jclass clazz, jboolean enable) {
    if (enable) {
        ALOGD_IF_SLOW(100, "Excessive delay in autosuspend_enable() while turning screen off");
        autosuspend_enable();
    } else {
        ALOGD_IF_SLOW(100, "Excessive delay in autosuspend_disable() while turning screen on");
        autosuspend_disable();
    }
}

// ----------------------------------------------------------------------------

static JNINativeMethod gPowerManagerServiceMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()V",
            (void*) nativeInit },
    { "nativeSetPowerState", "(ZZ)V",
            (void*) nativeSetPowerState },
    { "nativeAcquireSuspendBlocker", "(Ljava/lang/String;)V",
            (void*) nativeAcquireSuspendBlocker },
    { "nativeReleaseSuspendBlocker", "(Ljava/lang/String;)V",
            (void*) nativeReleaseSuspendBlocker },
    { "nativeSetInteractive", "(Z)V",
            (void*) nativeSetInteractive },
    { "nativeSetAutoSuspend", "(Z)V",
            (void*) nativeSetAutoSuspend },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_server_PowerManagerService(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/power/PowerManagerService",
            gPowerManagerServiceMethods, NELEM(gPowerManagerServiceMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    // Callbacks

    jclass clazz;
    FIND_CLASS(clazz, "com/android/server/power/PowerManagerService");

    GET_METHOD_ID(gPowerManagerServiceClassInfo.wakeUpFromNative, clazz,
            "wakeUpFromNative", "(J)V");

    GET_METHOD_ID(gPowerManagerServiceClassInfo.goToSleepFromNative, clazz,
            "goToSleepFromNative", "(JI)V");

    GET_METHOD_ID(gPowerManagerServiceClassInfo.userActivityFromNative, clazz,
            "userActivityFromNative", "(JII)V");

    // Initialize
    for (int i = 0; i <= USER_ACTIVITY_EVENT_LAST; i++) {
        gLastEventTime[i] = LLONG_MIN;
    }
    gScreenOn = true;
    gScreenBright = true;
    gPowerManagerServiceObj = NULL;
    gPowerModule = NULL;
    return 0;
}

} /* namespace android */
