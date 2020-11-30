/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "SurfaceSession"

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_SurfaceSession.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include <gui/SurfaceComposerClient.h>

namespace android {

static struct {
    jfieldID mNativeClient;
} gSurfaceSessionClassInfo;


sp<SurfaceComposerClient> android_view_SurfaceSession_getClient(
        JNIEnv* env, jobject surfaceSessionObj) {
    return reinterpret_cast<SurfaceComposerClient*>(
            env->GetIntField(surfaceSessionObj, gSurfaceSessionClassInfo.mNativeClient));
}

/*wwxx wms study part5 三.7、
它首先创建一个 SurfaceComposerClient 对象client，接着再增加这个SurfaceComposerClient对象的强引用计数，
因为再接下来会将这个SurfaceComposerClient对象的地址值保存在参数clazz所描述的一个SurfaceSession对象的成员变量mClient中，
这相当于是参数clazz所描述的一个SurfaceSession对象引用了刚才所创建的SurfaceComposerClient对象client。

在前面Android应用程序与SurfaceFlinger服务的关系概述和学习计划这一系列的文章中，我们已经分析过SurfaceComposerClient类的作用了，
这主要就是用来在应用程序进程和SurfaceFlinger服务之间建立连接的，以便应用程序进程可以为运行在它里面的应用程序窗口请求SurfaceComposerClient创建绘制表面（Surface）的操作等。

这样，每一个Java层的SurfaceSession对象在C++层就都有一个对应的SurfaceComposerClient对象，因此，Java层的应用程序就可以通过SurfaceSession类来和SurfaceFlinger服务建立连接。

至此，我们就分析完成一个WindowState对象的创建过程了，通过这个过程我们就可以知道，每一个Activity组件窗口在WindowManagerService服务内部都有一个对应的WindowState对象，用来描述它的窗口状态。


至此，我们也分析完成Android应用程序窗口与WindowManagerService服务的连接过程了。
从这个连接过程以及前面Android应用程序窗口（Activity）的窗口对象（Window）的创建过程分析和Android应用程序窗口（Activity）的视图对象（View）的创建过程分析这两篇文章，
我们就可以知道，为了实现一个Activity组件的UI，无论是应用程序进程，还是WindowManagerService，都做了大量的工作，
例如，应用程序进程为它创建一个窗口（Window）对象、一个视图（View）对象、一个ViewRoot对象、一个W对象，
WindowManagerService服务为它创建一个 AppWindowToken 对象和一个 WindowState 对象。
此外，WindowManagerService服务还为一个Activity组件所运行在的应用程序进程创建了一个 Session 对象。
理解这些对象的实现以及作用对我们了解Android应用程序窗口的实现框架以及WindowManagerService服务的实现原理都是非常重要的。
*/
static jint nativeCreate(JNIEnv* env, jclass clazz) {
    SurfaceComposerClient* client = new SurfaceComposerClient();
    client->incStrong((void*)nativeCreate);
    return reinterpret_cast<jint>(client);
}

static void nativeDestroy(JNIEnv* env, jclass clazz, jint ptr) {
    SurfaceComposerClient* client = reinterpret_cast<SurfaceComposerClient*>(ptr);
    client->decStrong((void*)nativeCreate);
}

static void nativeKill(JNIEnv* env, jclass clazz, jint ptr) {
    SurfaceComposerClient* client = reinterpret_cast<SurfaceComposerClient*>(ptr);
    client->dispose();
}


static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeCreate", "()I",
            (void*)nativeCreate },
    { "nativeDestroy", "(I)V",
            (void*)nativeDestroy },
    { "nativeKill", "(I)V",
            (void*)nativeKill }
};

int register_android_view_SurfaceSession(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/SurfaceSession",
            gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/view/SurfaceSession");
    gSurfaceSessionClassInfo.mNativeClient = env->GetFieldID(clazz, "mNativeClient", "I");
    return 0;
}

} // namespace android
