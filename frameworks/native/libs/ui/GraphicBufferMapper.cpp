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

#define LOG_TAG "GraphicBufferMapper"
#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#include <stdint.h>
#include <errno.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/Trace.h>

#include <ui/GraphicBufferMapper.h>
#include <ui/Rect.h>

#include <hardware/gralloc.h>


namespace android {
// ---------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE( GraphicBufferMapper )

GraphicBufferMapper::GraphicBufferMapper()
    : mAllocMod(0)
{
    hw_module_t const* module;
    int err = hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &module);
    ALOGE_IF(err, "FATAL: can't find the %s module", GRALLOC_HARDWARE_MODULE_ID);
    if (err == 0) {
        mAllocMod = (gralloc_module_t const *)module;
    }
}

status_t GraphicBufferMapper::registerBuffer(buffer_handle_t handle)
{
    ATRACE_CALL();
    status_t err;

    err = mAllocMod->registerBuffer(mAllocMod, handle);

    ALOGW_IF(err, "registerBuffer(%p) failed %d (%s)",
            handle, err, strerror(-err));
    return err;
}

status_t GraphicBufferMapper::unregisterBuffer(buffer_handle_t handle)
{
    ATRACE_CALL();
    status_t err;

    err = mAllocMod->unregisterBuffer(mAllocMod, handle);

    ALOGW_IF(err, "unregisterBuffer(%p) failed %d (%s)",
            handle, err, strerror(-err));
    return err;
}
/*wwxx wms study part7 三.5

调用GraphicBufferMapper类的成员变量mAllocMod所描述的一个HAL模块Gralloc的成员函数lock来获得参数andle所描述的一个图形缓冲区的地址。

从前面Android应用程序请求SurfaceFlinger服务渲染Surface的过程分析一文可以知道，应用程序窗口所使用的图形缓冲区一般都是在HAL模块Gralloc中分配的，
因此，GraphicBufferMapper类的成员函数lock接下来就会调用成员变量mAllocMod所描述的一个HAL模块Gralloc的成员函数lock来获得参数andle所描述的一个图形缓冲区的地址，
并且保存在输出参数vaddr中。HAL模块Gralloc的成员函数lock的实现可以参考前面Android帧缓冲区（Frame Buffer）硬件抽象层（HAL）模块Gralloc的实现原理分析一文，这里不再详述。

这一步执行完成之后，返回到前面的Step 1中，即ViewRoot类的成员函数draw中，
接下来就会继续调用其成员变量mView所描述的一个DecorView对象的成员函数draw来在前面所获得一块画布上面绘制应用程序窗口的UI。
*/
status_t GraphicBufferMapper::lock(buffer_handle_t handle, 
        int usage, const Rect& bounds, void** vaddr)
{
    ATRACE_CALL();
    status_t err;

    err = mAllocMod->lock(mAllocMod, handle, usage,
            bounds.left, bounds.top, bounds.width(), bounds.height(),
            vaddr);

    ALOGW_IF(err, "lock(...) failed %d (%s)", err, strerror(-err));
    return err;
}

status_t GraphicBufferMapper::lockYCbCr(buffer_handle_t handle,
        int usage, const Rect& bounds, android_ycbcr *ycbcr)
{
    ATRACE_CALL();
    status_t err;

    err = mAllocMod->lock_ycbcr(mAllocMod, handle, usage,
            bounds.left, bounds.top, bounds.width(), bounds.height(),
            ycbcr);

    ALOGW_IF(err, "lock(...) failed %d (%s)", err, strerror(-err));
    return err;
}
/*wwxx wms study part7 三.14、
从前面的Step 5可以知道，参数handle所描述的图形缓冲区是在HAL模块Gralloc中分配的，
这个HAL模块Gralloc是由GraphicBufferMapper类的成员变量mAllocMod来描述的，
因此，函数就终就会调用GraphicBufferMapper类的成员变量mAllocMod所描述的一个HAL模块Gralloc的成员函数unlock来解锁参数andle所描述的一个图形缓冲区。
HAL模块Gralloc的成员函数unlock的实现可以参考前面Android帧缓冲区（Frame Buffer）硬件抽象层（HAL）模块Gralloc的实现原理分析一文，这里不再详述。


至此，我们就分析完成Android应用程序窗口的渲染过程了，从中就可以看出：

1. 渲染Android应用程序窗口UI需要经过三步曲：测量、布局、绘制。

2. Android应用程序窗口UI首先是使用Skia图形库API来绘制在一块画布上，实际地是绘制在这块画布里面的一个图形缓冲区中，这
   个图形缓冲区最终会被交给SurfaceFlinger服务，而SurfaceFlinger服务再使用OpenGL图形库API来将这个图形缓冲区渲染到硬件帧缓冲区中。

*/
status_t GraphicBufferMapper::unlock(buffer_handle_t handle)
{
    ATRACE_CALL();
    status_t err;

    err = mAllocMod->unlock(mAllocMod, handle);

    ALOGW_IF(err, "unlock(...) failed %d (%s)", err, strerror(-err));
    return err;
}

// ---------------------------------------------------------------------------
}; // namespace android
