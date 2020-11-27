/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.IActivityManager;
import android.app.QueuedWork;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

/**
 * Base class for code that will receive intents sent by sendBroadcast().
 *
 * <p>If you don't need to send broadcasts across applications, consider using
 * this class with {@link android.support.v4.content.LocalBroadcastManager} instead
 * of the more general facilities described below.  This will give you a much
 * more efficient implementation (no cross-process communication needed) and allow
 * you to avoid thinking about any security issues related to other applications
 * being able to receive or send your broadcasts.
 *
 * <p>You can either dynamically register an instance of this class with
 * {@link Context#registerReceiver Context.registerReceiver()}
 * or statically publish an implementation through the
 * {@link android.R.styleable#AndroidManifestReceiver &lt;receiver&gt;}
 * tag in your <code>AndroidManifest.xml</code>.
 * 
 * <p><em><strong>Note:</strong></em>
 * &nbsp;&nbsp;&nbsp;If registering a receiver in your
 * {@link android.app.Activity#onResume() Activity.onResume()}
 * implementation, you should unregister it in 
 * {@link android.app.Activity#onPause() Activity.onPause()}.
 * (You won't receive intents when paused, 
 * and this will cut down on unnecessary system overhead). Do not unregister in 
 * {@link android.app.Activity#onSaveInstanceState(android.os.Bundle) Activity.onSaveInstanceState()},
 * because this won't be called if the user moves back in the history
 * stack.
 * 
 * <p>There are two major classes of broadcasts that can be received:</p>
 * <ul>
 * <li> <b>Normal broadcasts</b> (sent with {@link Context#sendBroadcast(Intent)
 * Context.sendBroadcast}) are completely asynchronous.  All receivers of the
 * broadcast are run in an undefined order, often at the same time.  This is
 * more efficient, but means that receivers cannot use the result or abort
 * APIs included here.
 * <li> <b>Ordered broadcasts</b> (sent with {@link Context#sendOrderedBroadcast(Intent, String)
 * Context.sendOrderedBroadcast}) are delivered to one receiver at a time.
 * As each receiver executes in turn, it can propagate a result to the next
 * receiver, or it can completely abort the broadcast so that it won't be passed
 * to other receivers.  The order receivers run in can be controlled with the
 * {@link android.R.styleable#AndroidManifestIntentFilter_priority
 * android:priority} attribute of the matching intent-filter; receivers with
 * the same priority will be run in an arbitrary order.
 * </ul>
 * 
 * <p>Even in the case of normal broadcasts, the system may in some
 * situations revert to delivering the broadcast one receiver at a time.  In
 * particular, for receivers that may require the creation of a process, only
 * one will be run at a time to avoid overloading the system with new processes.
 * In this situation, however, the non-ordered semantics hold: these receivers still
 * cannot return results or abort their broadcast.</p>
 * 
 * <p>Note that, although the Intent class is used for sending and receiving
 * these broadcasts, the Intent broadcast mechanism here is completely separate
 * from Intents that are used to start Activities with
 * {@link Context#startActivity Context.startActivity()}.
 * There is no way for a BroadcastReceiver
 * to see or capture Intents used with startActivity(); likewise, when
 * you broadcast an Intent, you will never find or start an Activity.
 * These two operations are semantically very different: starting an
 * Activity with an Intent is a foreground operation that modifies what the
 * user is currently interacting with; broadcasting an Intent is a background
 * operation that the user is not normally aware of.
 * 
 * <p>The BroadcastReceiver class (when launched as a component through
 * a manifest's {@link android.R.styleable#AndroidManifestReceiver &lt;receiver&gt;}
 * tag) is an important part of an
 * <a href="{@docRoot}guide/topics/fundamentals.html#lcycles">application's overall lifecycle</a>.</p>
 * 
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#Security">Security</a>
 * <li><a href="#ReceiverLifecycle">Receiver Lifecycle</a>
 * <li><a href="#ProcessLifecycle">Process Lifecycle</a>
 * </ol>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about how to use this class to receive and resolve intents, read the
 * <a href="{@docRoot}guide/topics/intents/intents-filters.html">Intents and Intent Filters</a>
 * developer guide.</p>
 * </div>
 *
 * <a name="Security"></a>
 * <h3>Security</h3>
 *
 * <p>Receivers used with the {@link Context} APIs are by their nature a
 * cross-application facility, so you must consider how other applications
 * may be able to abuse your use of them.  Some things to consider are:
 *
 * <ul>
 * <li><p>The Intent namespace is global.  Make sure that Intent action names and
 * other strings are written in a namespace you own, or else you may inadvertently
 * conflict with other applications.
 * <li><p>When you use {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)},
 * <em>any</em> application may send broadcasts to that registered receiver.  You can
 * control who can send broadcasts to it through permissions described below.
 * <li><p>When you publish a receiver in your application's manifest and specify
 * intent-filters for it, any other application can send broadcasts to it regardless
 * of the filters you specify.  To prevent others from sending to it, make it
 * unavailable to them with <code>android:exported="false"</code>.
 * <li><p>When you use {@link Context#sendBroadcast(Intent)} or related methods,
 * normally any other application can receive these broadcasts.  You can control who
 * can receive such broadcasts through permissions described below.  Alternatively,
 * starting with {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH}, you
 * can also safely restrict the broadcast to a single application with
 * {@link Intent#setPackage(String) Intent.setPackage}
 * </ul>
 *
 * <p>None of these issues exist when using
 * {@link android.support.v4.content.LocalBroadcastManager}, since intents
 * broadcast it never go outside of the current process.
 *
 * <p>Access permissions can be enforced by either the sender or receiver
 * of a broadcast.
 *
 * <p>To enforce a permission when sending, you supply a non-null
 * <var>permission</var> argument to
 * {@link Context#sendBroadcast(Intent, String)} or
 * {@link Context#sendOrderedBroadcast(Intent, String, BroadcastReceiver, android.os.Handler, int, String, Bundle)}.
 * Only receivers who have been granted this permission
 * (by requesting it with the
 * {@link android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
 * tag in their <code>AndroidManifest.xml</code>) will be able to receive
 * the broadcast.
 *
 * <p>To enforce a permission when receiving, you supply a non-null
 * <var>permission</var> when registering your receiver -- either when calling
 * {@link Context#registerReceiver(BroadcastReceiver, IntentFilter, String, android.os.Handler)}
 * or in the static
 * {@link android.R.styleable#AndroidManifestReceiver &lt;receiver&gt;}
 * tag in your <code>AndroidManifest.xml</code>.  Only broadcasters who have
 * been granted this permission (by requesting it with the
 * {@link android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
 * tag in their <code>AndroidManifest.xml</code>) will be able to send an
 * Intent to the receiver.
 *
 * <p>See the <a href="{@docRoot}guide/topics/security/security.html">Security and Permissions</a>
 * document for more information on permissions and security in general.
 *
 * <a name="ReceiverLifecycle"></a>
 * <h3>Receiver Lifecycle</h3>
 * 
 * <p>A BroadcastReceiver object is only valid for the duration of the call
 * to {@link #onReceive}.  Once your code returns from this function,
 * the system considers the object to be finished and no longer active.
 * 
 * <p>This has important repercussions to what you can do in an
 * {@link #onReceive} implementation: anything that requires asynchronous
 * operation is not available, because you will need to return from the
 * function to handle the asynchronous operation, but at that point the
 * BroadcastReceiver is no longer active and thus the system is free to kill
 * its process before the asynchronous operation completes.
 * 
 * <p>In particular, you may <i>not</i> show a dialog or bind to a service from
 * within a BroadcastReceiver.  For the former, you should instead use the
 * {@link android.app.NotificationManager} API.  For the latter, you can
 * use {@link android.content.Context#startService Context.startService()} to
 * send a command to the service.
 *
 * <a name="ProcessLifecycle"></a>
 * <h3>Process Lifecycle</h3>
 * 
 * <p>A process that is currently executing a BroadcastReceiver (that is,
 * currently running the code in its {@link #onReceive} method) is
 * considered to be a foreground process and will be kept running by the
 * system except under cases of extreme memory pressure.
 * 
 * <p>Once you return from onReceive(), the BroadcastReceiver is no longer
 * active, and its hosting process is only as important as any other application
 * components that are running in it.  This is especially important because if
 * that process was only hosting the BroadcastReceiver (a common case for
 * applications that the user has never or not recently interacted with), then
 * upon returning from onReceive() the system will consider its process
 * to be empty and aggressively kill it so that resources are available for other
 * more important processes.
 * 
 * <p>This means that for longer-running operations you will often use
 * a {@link android.app.Service} in conjunction with a BroadcastReceiver to keep
 * the containing process active for the entire time of your operation.
 */

/*wwxx
BroadcastReceiver 是一种很简单的组件，甚至在 ActivityThread 中都没有管理它的数据结构。因为 BroadcastReceiver 的本质就是通过Intent 来执行应用中的一个方法，
在应用中并不需要一个长期存在的对象。虽然在应用中不需要专门的数据结构来管理 BroadcastReceiver ，但是，在 ActivityManagerService 中还是需要的，
因为Broadcast Receiver 可以在程序运行时动态向AMS注册，AMS 中需要有数据结构来管理动态接收者。

BroadcastReceiver可以分成两类。

静态接收者:静态接收者是指在AndroidManifest中通过标签<receiver>指定的接收者。
动态接收者:动态接收者是指通过AMS 的 registerReceiver()接口注册的接收者。动态注册的好处是使用更加灵活。如果不需要接收广播了，可以通过unregisterReceiver()接口取消注册。


可以去看一个 AndroidManifest.xml 文件中定义静态接收者的例子:

标签<receiver>的属性 android:name 的值是应用中定义的 BroadcastReceiver 的派生类的名称。
同时还必须定义<intent-filter>标签，用来指定接收的Intent的种类。标签<intent-filter>中可以通过属性android:priority来指定接收的优先级。

BroadcastReceiver最重要的成员如下:

public abstract class BroadcastReceiver {
    private PendingResult mPendingResult;
    public abstract void onReceive(Context context, Intent intent);
}

实现一个广播接收器只需要从 BroadcastReceiver 中派生一个类，然后重载它的抽象方法 onReceive() 就可以了。这是最简单的广播用法。

BroadcastReceiver还有一个mPendingResult变量，有什么作用呢?

考虑这样一种情况，如果接收到广播后需要很长的时间来处理，而且还需要返回处理结果，该怎么办? BroadcastReceiver 对象的 onReceive() 方法如果长时间不返回，会引发ANR，
因此如果要执行耗时的操作，必须在其他线程中完成。但是在其他线程中完成处理后，如何把处理的结果传递回 ActivityManagerService 呢?这就需要用到 mPendingResult 变量了。
这时，在继承类的 onReceive() 方法中需要先调用 goAsync() 方法来得到 mPendingResult 对象:，见本文件goAsync()方法处。








广播的种类

从广播的发送方式来划分，有3类广播。

·普通广播:通过Context中的方法 sendBroadcast 和 sendBroadcastAsUser 发送的广播属于普通广播。普通广播的特点是发送给系统当前的所有注册的接收者，广播接收者接收广播的顺序也是不确定的。

·有序广播:和普通广播不同的是，有序广播的发送顺序是按照接收者的优先级来决定的,系统默认设置范围是-1000~1000，但实际上没有明确的数值限定，优先级最高可以是2147483647,也就是32位有符号整数的最大值，
    如果优先级相同，则先注册的接收者会先收到广播，而且动态注册的接收者比静态注册的接收者优先收到广播。有序广播的另一个特点是接收者可以打断广播的发送，如果接收者不希望广播继续传递，可以通过返回值来中止传递。
    同时接收者还可以篡改广播的内容，正因为如此，设置广播的高优先级就有意义了。例如几个应用同时注册了短信的接收广播。优先级高的收到后可以不让广播继续，这样其他的应用就接收不到了。
    有序广播通过 Context 的 sendOrderedBroadcast() 方法和 sendOrderedBroadcastAsUser() 方法发送。

粘性广播:粘性广播是很有用的一种广播方式，和前面两种广播不同的是，粘性广播能发给系统中以后注册的接收者，甚至是新安装的应用中的接收者，而前面两种广播都只能发给系统已经注册的接收者。
    粘性广播有两种，一种是普通粘性广播，通过 sendStickyBroadcast() 方法发送，另一种是有序粘性广播，通过 sendStickyOrderedBroadcast(）方法发送。



广播的数据结构
在 AactivityManagerService 中，所有注册的接收者放在成员变量 mRegisteredReceivers 中，定义如下:

final HashMap<IBinder,ReceiverList> mRegisteredReceivers;

mRegisteredReceivers 是一个 HashMap 类型的变量，使用接收者的 IBinder 作为 key 来存储接

收者的对象 ReceiverList 。因为一个接收者中可能包含有多个 IntentFilter ，所以接收者对象是一个数组，定义如下:

final class ReceiverList extends ArrayList<BroadcastFilter> ，可以去 ReceiverList.java 文件看看。

上面定义中的 BroadcastFilter是从 IntentFilter 类派生，定义如下: 

final class BroadcastFilter extends IntentFilter {
    // Back-pointer to the list this filter is in.
    final ReceiverList receiverList;   //所属recevier的引用
    final String packageName;          //所在应用的包名
    final String requiredPermission;   //权限字符串 
    final int owningUid;               //所在应用的uid
    final int owningUserId;            //所在应用的UserId
    ...
}



发送广播时，AMS 中收到的广播消息首先保存在 mBroadcastQueues 对象中，然后再发送给用户进程中的接收者。 mBroadcastQueues 是一个只有两个元素的数组，定义如下:去AMS看看
final BroadcastQueue[] mBroadcastQueues = new BroadcastQueue [2];


广播的注册过程 
直接看AMS public Intent registerReceiver 函数处吧。


广播的发送过程
直接看AMS public final int broadcastIntent 函数处理。

*/

public abstract class BroadcastReceiver {
    private PendingResult mPendingResult;
    private boolean mDebugUnregister;
    
    /**
     * State for a result that is pending for a broadcast receiver.  Returned
     * by {@link BroadcastReceiver#goAsync() goAsync()}
     * while in {@link BroadcastReceiver#onReceive BroadcastReceiver.onReceive()}.
     * This allows you to return from onReceive() without having the broadcast
     * terminate; you must call {@link #finish()} once you are done with the
     * broadcast.  This allows you to process the broadcast off of the main
     * thread of your app.
     * 
     * <p>Note on threading: the state inside of this class is not itself
     * thread-safe, however you can use it from any thread if you properly
     * sure that you do not have races.  Typically this means you will hand
     * the entire object to another thread, which will be solely responsible
     * for setting any results and finally calling {@link #finish()}.
     */
    public static class PendingResult {
        /** @hide */
        public static final int TYPE_COMPONENT = 0;
        /** @hide */
        public static final int TYPE_REGISTERED = 1;
        /** @hide */
        public static final int TYPE_UNREGISTERED = 2;
        
        final int mType;
        final boolean mOrderedHint;
        final boolean mInitialStickyHint;
        final IBinder mToken;
        final int mSendingUser;
        
        int mResultCode;
        String mResultData;
        Bundle mResultExtras;
        boolean mAbortBroadcast;
        boolean mFinished;

        /** @hide */
        public PendingResult(int resultCode, String resultData, Bundle resultExtras,
                int type, boolean ordered, boolean sticky, IBinder token, int userId) {
            mResultCode = resultCode;
            mResultData = resultData;
            mResultExtras = resultExtras;
            mType = type;
            mOrderedHint = ordered;
            mInitialStickyHint = sticky;
            mToken = token;
            mSendingUser = userId;
        }
        
        /**
         * Version of {@link BroadcastReceiver#setResultCode(int)
         * BroadcastReceiver.setResultCode(int)} for
         * asynchronous broadcast handling.
         */
        public final void setResultCode(int code) {
            checkSynchronousHint();
            mResultCode = code;
        }

        /**
         * Version of {@link BroadcastReceiver#getResultCode()
         * BroadcastReceiver.getResultCode()} for
         * asynchronous broadcast handling.
         */
        public final int getResultCode() {
            return mResultCode;
        }

        /**
         * Version of {@link BroadcastReceiver#setResultData(String)
         * BroadcastReceiver.setResultData(String)} for
         * asynchronous broadcast handling.
         */
        public final void setResultData(String data) {
            checkSynchronousHint();
            mResultData = data;
        }

        /**
         * Version of {@link BroadcastReceiver#getResultData()
         * BroadcastReceiver.getResultData()} for
         * asynchronous broadcast handling.
         */
        public final String getResultData() {
            return mResultData;
        }

        /**
         * Version of {@link BroadcastReceiver#setResultExtras(Bundle)
         * BroadcastReceiver.setResultExtras(Bundle)} for
         * asynchronous broadcast handling.
         */
        public final void setResultExtras(Bundle extras) {
            checkSynchronousHint();
            mResultExtras = extras;
        }

        /**
         * Version of {@link BroadcastReceiver#getResultExtras(boolean)
         * BroadcastReceiver.getResultExtras(boolean)} for
         * asynchronous broadcast handling.
         */
        public final Bundle getResultExtras(boolean makeMap) {
            Bundle e = mResultExtras;
            if (!makeMap) return e;
            if (e == null) mResultExtras = e = new Bundle();
            return e;
        }

        /**
         * Version of {@link BroadcastReceiver#setResult(int, String, Bundle)
         * BroadcastReceiver.setResult(int, String, Bundle)} for
         * asynchronous broadcast handling.
         */
        public final void setResult(int code, String data, Bundle extras) {
            checkSynchronousHint();
            mResultCode = code;
            mResultData = data;
            mResultExtras = extras;
        }
     
        /**
         * Version of {@link BroadcastReceiver#getAbortBroadcast()
         * BroadcastReceiver.getAbortBroadcast()} for
         * asynchronous broadcast handling.
         */
        public final boolean getAbortBroadcast() {
            return mAbortBroadcast;
        }

        /**
         * Version of {@link BroadcastReceiver#abortBroadcast()
         * BroadcastReceiver.abortBroadcast()} for
         * asynchronous broadcast handling.
         */
        public final void abortBroadcast() {
            checkSynchronousHint();
            mAbortBroadcast = true;
        }
        
        /**
         * Version of {@link BroadcastReceiver#clearAbortBroadcast()
         * BroadcastReceiver.clearAbortBroadcast()} for
         * asynchronous broadcast handling.
         */
        public final void clearAbortBroadcast() {
            mAbortBroadcast = false;
        }
        
        /**
         * Finish the broadcast.  The current result will be sent and the
         * next broadcast will proceed.
         */
        /*wwxx
        finish() 方法中有几处判断条件,但是各条分支最终都会执行 sendFinished() 方法。在 sendFinished() 方法中通过调用 finishReceiver() 方法将结果传递给了 ActivityManagerServcie，代码如下:见本文函数定义处
        */
        public final void finish() {
            if (mType == TYPE_COMPONENT) {
                final IActivityManager mgr = ActivityManagerNative.getDefault();
                if (QueuedWork.hasPendingWork()) {
                    // If this is a broadcast component, we need to make sure any
                    // queued work is complete before telling AM we are done, so
                    // we don't have our process killed before that.  We now know
                    // there is pending work; put another piece of work at the end
                    // of the list to finish the broadcast, so we don't block this
                    // thread (which may be the main thread) to have it finished.
                    //
                    // Note that we don't need to use QueuedWork.add() with the
                    // runnable, since we know the AM is waiting for us until the
                    // executor gets to it.
                    QueuedWork.singleThreadExecutor().execute( new Runnable() {
                        @Override public void run() {
                            if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                    "Finishing broadcast after work to component " + mToken);
                            sendFinished(mgr);
                        }
                    });
                } else {
                    if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                            "Finishing broadcast to component " + mToken);
                    sendFinished(mgr);
                }
            } else if (mOrderedHint && mType != TYPE_UNREGISTERED) {
                if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                        "Finishing broadcast to " + mToken);
                final IActivityManager mgr = ActivityManagerNative.getDefault();
                sendFinished(mgr);
            }
        }
        
        /** @hide */
        public void setExtrasClassLoader(ClassLoader cl) {
            if (mResultExtras != null) {
                mResultExtras.setClassLoader(cl);
            }
        }
        
        /** @hide */
        public void sendFinished(IActivityManager am) {
            synchronized (this) {
                if (mFinished) {
                    throw new IllegalStateException("Broadcast already finished");
                }
                mFinished = true;
            
                try {
                    if (mResultExtras != null) {
                        mResultExtras.setAllowFds(false);
                    }
                    if (mOrderedHint) {
                        am.finishReceiver(mToken, mResultCode, mResultData, mResultExtras,
                                mAbortBroadcast);
                    } else {
                        // This broadcast was sent to a component; it is not ordered,
                        // but we still need to tell the activity manager we are done.
                        am.finishReceiver(mToken, 0, null, null, false);
                    }
                } catch (RemoteException ex) {
                }
            }
        }

        /** @hide */
        public int getSendingUserId() {
            return mSendingUser;
        }

        void checkSynchronousHint() {
            // Note that we don't assert when receiving the initial sticky value,
            // since that may have come from an ordered broadcast.  We'll catch
            // them later when the real broadcast happens again.
            if (mOrderedHint || mInitialStickyHint) {
                return;
            }
            RuntimeException e = new RuntimeException(
                    "BroadcastReceiver trying to return result during a non-ordered broadcast");
            e.fillInStackTrace();
            Log.e("BroadcastReceiver", e.getMessage(), e);
        }
    }
    
    public BroadcastReceiver() {
    }

    /**
     * This method is called when the BroadcastReceiver is receiving an Intent
     * broadcast.  During this time you can use the other methods on
     * BroadcastReceiver to view/modify the current result values.  This method
     * is always called within the main thread of its process, unless you
     * explicitly asked for it to be scheduled on a different thread using
     * {@link android.content.Context#registerReceiver(BroadcastReceiver,
     * IntentFilter, String, android.os.Handler)}. When it runs on the main
     * thread you should
     * never perform long-running operations in it (there is a timeout of
     * 10 seconds that the system allows before considering the receiver to
     * be blocked and a candidate to be killed). You cannot launch a popup dialog
     * in your implementation of onReceive().
     *
     * <p><b>If this BroadcastReceiver was launched through a &lt;receiver&gt; tag,
     * then the object is no longer alive after returning from this
     * function.</b>  This means you should not perform any operations that
     * return a result to you asynchronously -- in particular, for interacting
     * with services, you should use
     * {@link Context#startService(Intent)} instead of
     * {@link Context#bindService(Intent, ServiceConnection, int)}.  If you wish
     * to interact with a service that is already running, you can use
     * {@link #peekService}.
     * 
     * <p>The Intent filters used in {@link android.content.Context#registerReceiver}
     * and in application manifests are <em>not</em> guaranteed to be exclusive. They
     * are hints to the operating system about how to find suitable recipients. It is
     * possible for senders to force delivery to specific recipients, bypassing filter
     * resolution.  For this reason, {@link #onReceive(Context, Intent) onReceive()}
     * implementations should respond only to known actions, ignoring any unexpected
     * Intents that they may receive.
     * 
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    public abstract void onReceive(Context context, Intent intent);

    /**
     * This can be called by an application in {@link #onReceive} to allow
     * it to keep the broadcast active after returning from that function.
     * This does <em>not</em> change the expectation of being relatively
     * responsive to the broadcast (finishing it within 10s), but does allow
     * the implementation to move work related to it over to another thread
     * to avoid glitching the main UI thread due to disk IO.
     * 
     * @return Returns a {@link PendingResult} representing the result of
     * the active broadcast.  The BroadcastRecord itself is no longer active;
     * all data and other interaction must go through {@link PendingResult}
     * APIs.  The {@link PendingResult#finish PendingResult.finish()} method
     * must be called once processing of the broadcast is done.
     */
    /*wwxx
    goAsync() 方法将 mPendingResult 引用的 PendingResult 对象通过返回值传递出来，同时将 mPendingResult 的值设置为 null，这样当 onReceive() 返回后，
    ActivityManagerService 如果需要广播的返回结果将会等待。在 onReceive() 结束前，需要将 PendingResult 对象传递到新的线程中 ，在新线程处理完成后，
    必须调用 PendingResult 对象的 finish() 方法将结果传递到 AMS ，AMS 得到结果后，才会继续传递消息到下一个接收者。我们看看finish()方法的代码:

    */
    public final PendingResult goAsync() {
        PendingResult res = mPendingResult;
        mPendingResult = null;
        return res;
    }
    
    /**
     * Provide a binder to an already-running service.  This method is synchronous
     * and will not start the target service if it is not present, so it is safe
     * to call from {@link #onReceive}.
     * 
     * @param myContext The Context that had been passed to {@link #onReceive(Context, Intent)}
     * @param service The Intent indicating the service you wish to use.  See {@link
     * Context#startService(Intent)} for more information.
     */
    public IBinder peekService(Context myContext, Intent service) {
        IActivityManager am = ActivityManagerNative.getDefault();
        IBinder binder = null;
        try {
            service.prepareToLeaveProcess();
            binder = am.peekService(service, service.resolveTypeIfNeeded(
                    myContext.getContentResolver()));
        } catch (RemoteException e) {
        }
        return binder;
    }

    /**
     * Change the current result code of this broadcast; only works with
     * broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  Often uses the
     * Activity {@link android.app.Activity#RESULT_CANCELED} and
     * {@link android.app.Activity#RESULT_OK} constants, though the
     * actual meaning of this value is ultimately up to the broadcaster.
     * 
     * <p class="note">This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</p>
     * 
     * @param code The new result code.
     * 
     * @see #setResult(int, String, Bundle)
     */
    public final void setResultCode(int code) {
        checkSynchronousHint();
        mPendingResult.mResultCode = code;
    }

    /**
     * Retrieve the current result code, as set by the previous receiver.
     * 
     * @return int The current result code.
     */
    public final int getResultCode() {
        return mPendingResult != null ? mPendingResult.mResultCode : 0;
    }

    /**
     * Change the current result data of this broadcast; only works with
     * broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  This is an arbitrary
     * string whose interpretation is up to the broadcaster.
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     * 
     * @param data The new result data; may be null.
     * 
     * @see #setResult(int, String, Bundle)
     */
    public final void setResultData(String data) {
        checkSynchronousHint();
        mPendingResult.mResultData = data;
    }

    /**
     * Retrieve the current result data, as set by the previous receiver.
     * Often this is null.
     * 
     * @return String The current result data; may be null.
     */
    public final String getResultData() {
        return mPendingResult != null ? mPendingResult.mResultData : null;
    }

    /**
     * Change the current result extras of this broadcast; only works with
     * broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  This is a Bundle
     * holding arbitrary data, whose interpretation is up to the
     * broadcaster.  Can be set to null.  Calling this method completely
     * replaces the current map (if any).
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     * 
     * @param extras The new extra data map; may be null.
     * 
     * @see #setResult(int, String, Bundle)
     */
    public final void setResultExtras(Bundle extras) {
        checkSynchronousHint();
        mPendingResult.mResultExtras = extras;
    }

    /**
     * Retrieve the current result extra data, as set by the previous receiver.
     * Any changes you make to the returned Map will be propagated to the next
     * receiver.
     * 
     * @param makeMap If true then a new empty Map will be made for you if the
     *                current Map is null; if false you should be prepared to
     *                receive a null Map.
     * 
     * @return Map The current extras map.
     */
    public final Bundle getResultExtras(boolean makeMap) {
        if (mPendingResult == null) {
            return null;
        }
        Bundle e = mPendingResult.mResultExtras;
        if (!makeMap) return e;
        if (e == null) mPendingResult.mResultExtras = e = new Bundle();
        return e;
    }

    /**
     * Change all of the result data returned from this broadcasts; only works
     * with broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  All current result data is replaced
     * by the value given to this method.
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     * 
     * @param code The new result code.  Often uses the
     * Activity {@link android.app.Activity#RESULT_CANCELED} and
     * {@link android.app.Activity#RESULT_OK} constants, though the
     * actual meaning of this value is ultimately up to the broadcaster.
     * @param data The new result data.  This is an arbitrary
     * string whose interpretation is up to the broadcaster; may be null.
     * @param extras The new extra data map.  This is a Bundle
     * holding arbitrary data, whose interpretation is up to the
     * broadcaster.  Can be set to null.  This completely
     * replaces the current map (if any).
     */
    public final void setResult(int code, String data, Bundle extras) {
        checkSynchronousHint();
        mPendingResult.mResultCode = code;
        mPendingResult.mResultData = data;
        mPendingResult.mResultExtras = extras;
    }
 
    /**
     * Returns the flag indicating whether or not this receiver should
     * abort the current broadcast.
     * 
     * @return True if the broadcast should be aborted.
     */
    public final boolean getAbortBroadcast() {
        return mPendingResult != null ? mPendingResult.mAbortBroadcast : false;
    }

    /**
     * Sets the flag indicating that this receiver should abort the
     * current broadcast; only works with broadcasts sent through
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast}.  This will prevent
     * any other broadcast receivers from receiving the broadcast. It will still
     * call {@link #onReceive} of the BroadcastReceiver that the caller of 
     * {@link Context#sendOrderedBroadcast(Intent, String)
     * Context.sendOrderedBroadcast} passed in.
     * 
     * <p><strong>This method does not work with non-ordered broadcasts such
     * as those sent with {@link Context#sendBroadcast(Intent)
     * Context.sendBroadcast}</strong></p>
     */
    public final void abortBroadcast() {
        checkSynchronousHint();
        mPendingResult.mAbortBroadcast = true;
    }
    
    /**
     * Clears the flag indicating that this receiver should abort the current
     * broadcast.
     */
    public final void clearAbortBroadcast() {
        if (mPendingResult != null) {
            mPendingResult.mAbortBroadcast = false;
        }
    }
    
    /**
     * Returns true if the receiver is currently processing an ordered
     * broadcast.
     */
    public final boolean isOrderedBroadcast() {
        return mPendingResult != null ? mPendingResult.mOrderedHint : false;
    }
    
    /**
     * Returns true if the receiver is currently processing the initial
     * value of a sticky broadcast -- that is, the value that was last
     * broadcast and is currently held in the sticky cache, so this is
     * not directly the result of a broadcast right now.
     */
    public final boolean isInitialStickyBroadcast() {
        return mPendingResult != null ? mPendingResult.mInitialStickyHint : false;
    }
    
    /**
     * For internal use, sets the hint about whether this BroadcastReceiver is
     * running in ordered mode.
     */
    public final void setOrderedHint(boolean isOrdered) {
        // Accidentally left in the SDK.
    }
    
    /**
     * For internal use to set the result data that is active. @hide
     */
    public final void setPendingResult(PendingResult result) {
        mPendingResult = result;
    }
    
    /**
     * For internal use to set the result data that is active. @hide
     */
    public final PendingResult getPendingResult() {
        return mPendingResult;
    }
    
    /** @hide */
    public int getSendingUserId() {
        return mPendingResult.mSendingUser;
    }

    /**
     * Control inclusion of debugging help for mismatched
     * calls to {@link Context#registerReceiver(BroadcastReceiver, IntentFilter)
     * Context.registerReceiver()}.
     * If called with true, before given to registerReceiver(), then the
     * callstack of the following {@link Context#unregisterReceiver(BroadcastReceiver)
     * Context.unregisterReceiver()} call is retained, to be printed if a later
     * incorrect unregister call is made.  Note that doing this requires retaining
     * information about the BroadcastReceiver for the lifetime of the app,
     * resulting in a leak -- this should only be used for debugging.
     */
    public final void setDebugUnregister(boolean debug) {
        mDebugUnregister = debug;
    }
    
    /**
     * Return the last value given to {@link #setDebugUnregister}.
     */
    public final boolean getDebugUnregister() {
        return mDebugUnregister;
    }
    
    void checkSynchronousHint() {
        if (mPendingResult == null) {
            throw new IllegalStateException("Call while result is not pending");
        }
        
        // Note that we don't assert when receiving the initial sticky value,
        // since that may have come from an ordered broadcast.  We'll catch
        // them later when the real broadcast happens again.
        if (mPendingResult.mOrderedHint || mPendingResult.mInitialStickyHint) {
            return;
        }
        RuntimeException e = new RuntimeException(
                "BroadcastReceiver trying to return result during a non-ordered broadcast");
        e.fillInStackTrace();
        Log.e("BroadcastReceiver", e.getMessage(), e);
    }
}

