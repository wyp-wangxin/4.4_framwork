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

#ifndef ANDROID_SURFACE_FLINGER_H
#define ANDROID_SURFACE_FLINGER_H

#include <stdint.h>
#include <sys/types.h>

#include <EGL/egl.h>

/*
 * NOTE: Make sure this file doesn't include  anything from <gl/ > or <gl2/ >
 */

#include <cutils/compiler.h>

#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/threads.h>

#include <binder/IMemory.h>

#include <ui/PixelFormat.h>

#include <gui/ISurfaceComposer.h>
#include <gui/ISurfaceComposerClient.h>

#include <hardware/hwcomposer_defs.h>

#include <private/gui/LayerState.h>

#include "Barrier.h"
#include "DisplayDevice.h"
#include "DispSync.h"
#include "FrameTracker.h"
#include "MessageQueue.h"

#include "DisplayHardware/HWComposer.h"
#include "Effects/Daltonizer.h"

namespace android {


/*wwxx wms study part8 2、
    SurfaceFlinger类有两个类型为State的成员变量mCurrentState和mDrawingState。其中，成员变量mCurrentState用来描述系统下一次要渲染的UI的状态；
    而 mDrawingState 用来描述当前正要渲染的UI的状态。

    State 类用来描述一个UI状态，它有四个重要的成员变量 layersSortedByZ 、 orientation 、 orientationType 和 freezeDisplay 。4.4 里面只有两个成员了。
    其中，成员变量 layersSortedByZ 是一个类型为 LayerVector 的向量，里面保存的系统所包含的Surface，每一个Surface使用一个LayerBase对象来描述，
    并且它们按照 Z轴顺序来排列；
    成员变量 orientation 和 orientationType 的类型均为uint8_t，它们用来描述屏幕的方向； 
    成员变量 freezeDisplay 的类型也是uint8_t，用来描述屏幕是否处于被冻结状态。

    SurfaceFlinger类的成员变量 mVisibleLayerSortedByZ （4.4无）是一个类型为sp<LayerBase>的Vector，它是用来保存SurfaceFlinger服务下一次要渲染的、处于可见状态的Surface的，
    它们是来自SurfaceFlinger类的成员变量 mDrawingState 所描述的一个State对象的成员变量layersSortedByZ的。

    SurfaceFlinger类的成员变量 mGraphicPlanes （4.4无）是一个类型为GraphicPlane的数组，它是用来描述系统所有的显示设备的。从这个数组的大小等于1可以知道，
    当前Android系统只支持一个显示设备。

    GraphicPlane 类（4.4无）有四个重要的成员变量 mHw 、 mOrientation 、 mWidth和mHeight 。
    其中，成员变量mHw指向一个DisplayHardware对象，用来描述一个硬件显示设备；成员变量mOrientation、mWidth和mHeight的类型均为int，分别用来描述一个硬件显示设备的旋转方向、宽度和高度。我们可以通过调用GraphicPlane类的成员函数setDisplayHardware和displayHardware来设备和获取一个GraphicPlane对象内部所包含的一个硬件显示设备。

    DisplayHardware类（4.4无）有一个重要的成员变量mNativeWindow，它是一个类型为FramebufferNativeWindow的强指针。
    FramebufferNativeWindow类是用来描述一个Android系统本地窗口，而这个窗口代表的是系统的硬件帧缓冲区。
    DisplayHardware类的成员函数flip是用来渲染系统UI的，即将后端的图形缓冲区翻转为前端的图形缓冲区，并且渲染在硬件帧缓冲区去。

    FramebufferNativeWindow类与在前面Android应用程序请求SurfaceFlinger服务创建Surface的过程分析一文中所介绍的Surface类的作用是一样的，
    即它是OpenGL库和Android的UI系统之间的一个桥梁。OpenGL库正是通过它的成员函数dequeueBuffer来获得一个用来填充UI数据的图形缓冲区，
    而通过它的成员函数queueBuffer来将一个已经填充好UI数据的图形缓冲区渲染到系统的帧缓冲区中去。

    FramebufferNativeWindow 类有三个重要的成员变量 fbDev 、 grDev 和 buffers 。
    其中，成员变量fbDev和grDev分别指向一个framebuffer_device_t设备和一个alloc_device_t设备。
    从前面Android帧缓冲区（Frame Buffer）硬件抽象层（HAL）模块Gralloc的实现原理分析一文可以知道，
    framebuffer_device_t设备和一个alloc_device_t设备是由HAL模块Gralloc来提供的，它们分别用来分配 图形缓冲区 和 渲染图形缓冲区 ；
    成员变量buffers是一个类型为 NativeBuffer 的数组，这个数组用来描述一个图形缓冲区堆栈，堆栈的大小为 NUM_FRAME_BUFFERS，这些图形缓冲区是直接在硬件帧缓冲区中分配的
    ，有别于Surface类所使用的图形缓冲区，因为后者所使用的图形缓冲区是在匿名共享内存分配的。

    了解了SurfaceFlinger类的重要成员变量之后，我们再来了解它的几个重要成员函数 threadLoop （4.4无） 、 waitForEvent 、
    signalEvent（4.4无）、 handleConsoleEvents （4.4无） 、 handleTransaction 、 handlePageFlip 、 handleRepaint（4.4无） 和 postFramebuffer 。

    SurfaceFlinger服务虽然是在System进程中启动的，但是它在启动的时候创建一个线程来专门负责渲染UI。
    为了方便描述，我们将这个线程称为UI渲染线程。UI渲染线程的执行函数就为SurfaceFlinger类的成员函数threadLoop，
    同时它有一个消息队列。当UI渲染线程不需要渲染UI时，它就会在SurfaceFlinger类的成员函数waitForEvent中睡眠等待，直到SurfaceFlinger服务需要执行新的UI渲染操作为止。

    SurfaceFlinger服务什么时候会需要执行新的UI渲染操作呢？当系统显示屏属性发生变化，或者应用程序窗口发生变化时，它就需要重新渲染系统的UI。
    这时候SurfaceFlinger服务就会从SurfaceFlinger类的成员函数waitEvent中唤醒，
    并且依次执行SurfaceFlinger类的成员函数handleConsoleEvents、handleTransaction、handlePageFlip、handleRepaint和postFramebuffer来具体执行渲染UI的操作。
    其中，成员函数handleConsoleEvents用来处理控制台事件；成员函数handleTransaction用来处理系统显示屏属性变化以及应用程序窗口属性变化；
    成员函数handlePageFlip用来获得应用程序窗口下一次要渲染的图形缓冲区，即设置应用程序窗口的活动图形缓冲区；成员函数handleRepaint用来重绘应用程序窗口；
    成员函数postFramebuffer用来将系统UI渲染到硬件帧缓冲区中去。

    我们知道，应用程序是运行在与SurfaceFlinger服务不同的进程中的，而从前面Android应用程序请求SurfaceFlinger服务渲染Surface的过程分析一文又可以知道，
    每当应用程序需要更新自己的UI时，它们就会通过Binder进程间通信机制来通知SurfaceFlinger服务。SurfaceFlinger服务接到这个通知之后，
    就会调用SurfaceFlinger类的成员函数signalEvent来唤醒UI渲染线程，以便它可以执行渲染UI的操作。注意，SurfaceFlinger服务是通过Binder线程来获得应用程序的请求的，
    因此，这时候SurfaceFlinger服务的UI渲染线程实际上是被Binder线程唤醒的。
    SurfaceFlinger类的成员函数signalEvent实际上是通过向UI渲染线程的消息队列发送一个类型为INVALIDATE的消息来唤醒UI渲染线程的。
    
    前面提到， SurfaceFlinger服务在在执行UI渲染操作时，需要调用SurfaceFlinger类的成员函数handleConsoleEvents来处理控制台事件。
    这怎么理解呢？原来，SurfaceFlinger服务在启动的时候，还会创建另外一个线程来监控由内核发出的帧缓冲区硬件事件。
    为了方便描述，我们将这个线程称为控制台事件监控线程。每当帧缓冲区要进入睡眠状态时，内核就会发出一个睡眠事件，这时候SurfaceFlinger服务就会执行一个释放屏幕的操作；
    而当帧缓冲区从睡眠状态唤醒时，内核就会发出一个唤醒事件，这时候SurfaceFlinger服务就会执行一个获取屏幕的操作。

    这样，我们就简要介绍完了SurfaceFlinger类的定义。从这些介绍可以知道：

       1. SurfaceFlinger服务通过一个GraphicPlane对象来管理系统的显示设备；

       2. SurfaceFlinger服务有三种类型的线程，它们分别是Binder线程、控制台事件监控线程和UI渲染线程；

       3. SurfaceFlinger服务是在UI渲染线程中执行渲染系统UI的操作的。
*/
// ---------------------------------------------------------------------------

class Client;
class DisplayEventConnection;
class EventThread;
class IGraphicBufferAlloc;
class Layer;
class LayerDim;
class Surface;
class RenderEngine;
class EventControlThread;

// ---------------------------------------------------------------------------

enum {
    eTransactionNeeded        = 0x01,
    eTraversalNeeded          = 0x02,
    eDisplayTransactionNeeded = 0x04,
    eTransactionMask          = 0x07
};

class SurfaceFlinger : public BnSurfaceComposer,
                       private IBinder::DeathRecipient,
                       private HWComposer::EventHandler
{
public:
    static char const* getServiceName() ANDROID_API {
        return "SurfaceFlinger";
    }

    SurfaceFlinger() ANDROID_API;

    // must be called before clients can connect
    void init() ANDROID_API;

    // starts SurfaceFlinger main loop in the current thread
    void run() ANDROID_API;

    enum {
        EVENT_VSYNC = HWC_EVENT_VSYNC
    };

    // post an asynchronous message to the main thread
    status_t postMessageAsync(const sp<MessageBase>& msg, nsecs_t reltime = 0, uint32_t flags = 0);

    // post a synchronous message to the main thread
    status_t postMessageSync(const sp<MessageBase>& msg, nsecs_t reltime = 0, uint32_t flags = 0);

    // force full composition on all displays
    void repaintEverything();

    // returns the default Display
    sp<const DisplayDevice> getDefaultDisplayDevice() const {
        return getDisplayDevice(mBuiltinDisplays[DisplayDevice::DISPLAY_PRIMARY]);
    }

    // utility function to delete a texture on the main thread
    void deleteTextureAsync(uint32_t texture);

    // enable/disable h/w composer event
    // TODO: this should be made accessible only to EventThread
    void eventControl(int disp, int event, int enabled);

    // called on the main thread by MessageQueue when an internal message
    // is received
    // TODO: this should be made accessible only to MessageQueue
    void onMessageReceived(int32_t what);

    // for debugging only
    // TODO: this should be made accessible only to HWComposer
    const Vector< sp<Layer> >& getLayerSortedByZForHwcDisplay(int id);

    RenderEngine& getRenderEngine() const {
        return *mRenderEngine;
    }

private:
    friend class Client;
    friend class DisplayEventConnection;
    friend class Layer;
    friend class SurfaceTextureLayer;

    // This value is specified in number of frames.  Log frame stats at most
    // every half hour.
    enum { LOG_FRAME_STATS_PERIOD =  30*60*60 };

    // We're reference counted, never destroy SurfaceFlinger directly
    virtual ~SurfaceFlinger();

    /* ------------------------------------------------------------------------
     * Internal data structures
     */

    class LayerVector : public SortedVector< sp<Layer> > {
    public:
        LayerVector();
        LayerVector(const LayerVector& rhs);
        virtual int do_compare(const void* lhs, const void* rhs) const;
    };

    struct DisplayDeviceState {
        DisplayDeviceState();
        DisplayDeviceState(DisplayDevice::DisplayType type);
        bool isValid() const { return type >= 0; }
        bool isMainDisplay() const { return type == DisplayDevice::DISPLAY_PRIMARY; }
        bool isVirtualDisplay() const { return type >= DisplayDevice::DISPLAY_VIRTUAL; }
        DisplayDevice::DisplayType type;
        sp<IGraphicBufferProducer> surface;
        uint32_t layerStack;
        Rect viewport;
        Rect frame;
        uint8_t orientation;
        String8 displayName;
        bool isSecure;
    };

    struct State {
        LayerVector layersSortedByZ;
        DefaultKeyedVector< wp<IBinder>, DisplayDeviceState> displays;
    };

    /* ------------------------------------------------------------------------
     * IBinder interface
     */
    virtual status_t onTransact(uint32_t code, const Parcel& data,
        Parcel* reply, uint32_t flags);
    virtual status_t dump(int fd, const Vector<String16>& args);

    /* ------------------------------------------------------------------------
     * ISurfaceComposer interface
     */
    virtual sp<ISurfaceComposerClient> createConnection();
    virtual sp<IGraphicBufferAlloc> createGraphicBufferAlloc();
    virtual sp<IBinder> createDisplay(const String8& displayName, bool secure);
    virtual void destroyDisplay(const sp<IBinder>& display);
    virtual sp<IBinder> getBuiltInDisplay(int32_t id);
    virtual void setTransactionState(const Vector<ComposerState>& state,
            const Vector<DisplayState>& displays, uint32_t flags);
    virtual void bootFinished();
    virtual bool authenticateSurfaceTexture(
        const sp<IGraphicBufferProducer>& bufferProducer) const;
    virtual sp<IDisplayEventConnection> createDisplayEventConnection();
    virtual status_t captureScreen(const sp<IBinder>& display,
            const sp<IGraphicBufferProducer>& producer,
            uint32_t reqWidth, uint32_t reqHeight,
            uint32_t minLayerZ, uint32_t maxLayerZ);
    // called when screen needs to turn off
    virtual void blank(const sp<IBinder>& display);
    // called when screen is turning back on
    virtual void unblank(const sp<IBinder>& display);
    virtual status_t getDisplayInfo(const sp<IBinder>& display, DisplayInfo* info);

    /* ------------------------------------------------------------------------
     * DeathRecipient interface
     */
    virtual void binderDied(const wp<IBinder>& who);

    /* ------------------------------------------------------------------------
     * RefBase interface
     */
    virtual void onFirstRef();

    /* ------------------------------------------------------------------------
     * HWComposer::EventHandler interface
     */
    virtual void onVSyncReceived(int type, nsecs_t timestamp);
    virtual void onHotplugReceived(int disp, bool connected);

    /* ------------------------------------------------------------------------
     * Message handling
     */
    void waitForEvent();
    void signalTransaction();
    void signalLayerUpdate();
    void signalRefresh();

    // called on the main thread in response to initializeDisplays()
    void onInitializeDisplays();
    // called on the main thread in response to blank()
    void onScreenReleased(const sp<const DisplayDevice>& hw);
    // called on the main thread in response to unblank()
    void onScreenAcquired(const sp<const DisplayDevice>& hw);

    void handleMessageTransaction();
    void handleMessageInvalidate();
    void handleMessageRefresh();

    void handleTransaction(uint32_t transactionFlags);
    void handleTransactionLocked(uint32_t transactionFlags);

    /* handlePageFilp: this is were we latch a new buffer
     * if available and compute the dirty region.
     */
    void handlePageFlip();

    /* ------------------------------------------------------------------------
     * Transactions
     */
    uint32_t getTransactionFlags(uint32_t flags);
    uint32_t peekTransactionFlags(uint32_t flags);
    uint32_t setTransactionFlags(uint32_t flags);
    void commitTransaction();
    uint32_t setClientStateLocked(const sp<Client>& client, const layer_state_t& s);
    uint32_t setDisplayStateLocked(const DisplayState& s);

    /* ------------------------------------------------------------------------
     * Layer management
     */
    status_t createLayer(const String8& name, const sp<Client>& client,
            uint32_t w, uint32_t h, PixelFormat format, uint32_t flags,
            sp<IBinder>* handle, sp<IGraphicBufferProducer>* gbp);

    status_t createNormalLayer(const sp<Client>& client, const String8& name,
            uint32_t w, uint32_t h, uint32_t flags, PixelFormat& format,
            sp<IBinder>* outHandle, sp<IGraphicBufferProducer>* outGbp,
            sp<Layer>* outLayer);

    status_t createDimLayer(const sp<Client>& client, const String8& name,
            uint32_t w, uint32_t h, uint32_t flags, sp<IBinder>* outHandle,
            sp<IGraphicBufferProducer>* outGbp, sp<Layer>* outLayer);

    // called in response to the window-manager calling
    // ISurfaceComposerClient::destroySurface()
    status_t onLayerRemoved(const sp<Client>& client, const sp<IBinder>& handle);

    // called when all clients have released all their references to
    // this layer meaning it is entirely safe to destroy all
    // resources associated to this layer.
    status_t onLayerDestroyed(const wp<Layer>& layer);

    // remove a layer from SurfaceFlinger immediately
    status_t removeLayer(const sp<Layer>& layer);

    // add a layer to SurfaceFlinger
    void addClientLayer(const sp<Client>& client,
            const sp<IBinder>& handle,
            const sp<IGraphicBufferProducer>& gbc,
            const sp<Layer>& lbc);

    /* ------------------------------------------------------------------------
     * Boot animation, on/off animations and screen capture
     */

    void startBootAnim();

    void renderScreenImplLocked(
            const sp<const DisplayDevice>& hw,
            uint32_t reqWidth, uint32_t reqHeight,
            uint32_t minLayerZ, uint32_t maxLayerZ,
            bool yswap);

    status_t captureScreenImplLocked(
            const sp<const DisplayDevice>& hw,
            const sp<IGraphicBufferProducer>& producer,
            uint32_t reqWidth, uint32_t reqHeight,
            uint32_t minLayerZ, uint32_t maxLayerZ);

    /* ------------------------------------------------------------------------
     * EGL
     */
    static status_t selectConfigForAttribute(EGLDisplay dpy,
        EGLint const* attrs, EGLint attribute, EGLint value, EGLConfig* outConfig);
    static status_t selectEGLConfig(EGLDisplay disp, EGLint visualId,
        EGLint renderableType, EGLConfig* config);
    size_t getMaxTextureSize() const;
    size_t getMaxViewportDims() const;

    /* ------------------------------------------------------------------------
     * Display and layer stack management
     */
    // called when starting, or restarting after system_server death
    void initializeDisplays();

    // Create an IBinder for a builtin display and add it to current state
    void createBuiltinDisplayLocked(DisplayDevice::DisplayType type);

    // NOTE: can only be called from the main thread or with mStateLock held
    sp<const DisplayDevice> getDisplayDevice(const wp<IBinder>& dpy) const {
        return mDisplays.valueFor(dpy);
    }

    // NOTE: can only be called from the main thread or with mStateLock held
    sp<DisplayDevice> getDisplayDevice(const wp<IBinder>& dpy) {
        return mDisplays.valueFor(dpy);
    }

    // mark a region of a layer stack dirty. this updates the dirty
    // region of all screens presenting this layer stack.
    void invalidateLayerStack(uint32_t layerStack, const Region& dirty);

    // allocate a h/w composer display id
    int32_t allocateHwcDisplayId(DisplayDevice::DisplayType type);

    /* ------------------------------------------------------------------------
     * H/W composer
     */

    HWComposer& getHwComposer() const { return *mHwc; }

    /* ------------------------------------------------------------------------
     * Compositing
     */
    void invalidateHwcGeometry();
    static void computeVisibleRegions(
            const LayerVector& currentLayers, uint32_t layerStack,
            Region& dirtyRegion, Region& opaqueRegion);

    void preComposition();
    void postComposition();
    void rebuildLayerStacks();
    void setUpHWComposer();
    void doComposition();
    void doDebugFlashRegions();
    void doDisplayComposition(const sp<const DisplayDevice>& hw, const Region& dirtyRegion);
    void doComposeSurfaces(const sp<const DisplayDevice>& hw, const Region& dirty);

    void postFramebuffer();
    void drawWormhole(const sp<const DisplayDevice>& hw, const Region& region) const;

    /* ------------------------------------------------------------------------
     * Display management
     */

    /* ------------------------------------------------------------------------
     * VSync
     */
     void enableHardwareVsync();
     void disableHardwareVsync(bool makeUnavailable);
     void resyncToHardwareVsync(bool makeAvailable);

    /* ------------------------------------------------------------------------
     * Debugging & dumpsys
     */
    void listLayersLocked(const Vector<String16>& args, size_t& index, String8& result) const;
    void dumpStatsLocked(const Vector<String16>& args, size_t& index, String8& result) const;
    void clearStatsLocked(const Vector<String16>& args, size_t& index, String8& result);
    void dumpAllLocked(const Vector<String16>& args, size_t& index, String8& result) const;
    bool startDdmConnection();
    static void appendSfConfigString(String8& result);
    void checkScreenshot(size_t w, size_t s, size_t h, void const* vaddr,
            const sp<const DisplayDevice>& hw,
            uint32_t minLayerZ, uint32_t maxLayerZ);

    void logFrameStats();

    /* ------------------------------------------------------------------------
     * Attributes
     */

    // access must be protected by mStateLock
    mutable Mutex mStateLock;
    State mCurrentState;
    volatile int32_t mTransactionFlags;
    Condition mTransactionCV;
    bool mTransactionPending;
    bool mAnimTransactionPending;
    Vector< sp<Layer> > mLayersPendingRemoval;
    SortedVector< wp<IBinder> > mGraphicBufferProducerList;

    // protected by mStateLock (but we could use another lock)
    bool mLayersRemoved;

    // access must be protected by mInvalidateLock
    volatile int32_t mRepaintEverything;

    // constant members (no synchronization needed for access)
    HWComposer* mHwc;
    RenderEngine* mRenderEngine;
    nsecs_t mBootTime;
    bool mGpuToCpuSupported;
    sp<EventThread> mEventThread;
    sp<EventThread> mSFEventThread;
    sp<EventControlThread> mEventControlThread;
    EGLContext mEGLContext;
    EGLConfig mEGLConfig;
    EGLDisplay mEGLDisplay;
    EGLint mEGLNativeVisualId;
    sp<IBinder> mBuiltinDisplays[DisplayDevice::NUM_BUILTIN_DISPLAY_TYPES];

    // Can only accessed from the main thread, these members
    // don't need synchronization
    State mDrawingState;
    bool mVisibleRegionsDirty;
    bool mHwWorkListDirty;
    bool mAnimCompositionPending;

    // this may only be written from the main thread with mStateLock held
    // it may be read from other threads with mStateLock held
    DefaultKeyedVector< wp<IBinder>, sp<DisplayDevice> > mDisplays;

    // don't use a lock for these, we don't care
    int mDebugRegion;
    int mDebugDDMS;
    int mDebugDisableHWC;
    int mDebugDisableTransformHint;
    volatile nsecs_t mDebugInSwapBuffers;
    nsecs_t mLastSwapBufferTime;
    volatile nsecs_t mDebugInTransaction;
    nsecs_t mLastTransactionTime;
    bool mBootFinished;

    // these are thread safe
    mutable MessageQueue mEventQueue;
    FrameTracker mAnimFrameTracker;
    DispSync mPrimaryDispSync;

    // protected by mDestroyedLayerLock;
    mutable Mutex mDestroyedLayerLock;
    Vector<Layer const *> mDestroyedLayers;

    // protected by mHWVsyncLock
    Mutex mHWVsyncLock;
    bool mPrimaryHWVsyncEnabled;
    bool mHWVsyncAvailable;

    /* ------------------------------------------------------------------------
     * Feature prototyping
     */

    Daltonizer mDaltonizer;
    bool mDaltonize;
};

}; // namespace android

#endif // ANDROID_SURFACE_FLINGER_H
