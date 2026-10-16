LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := k9native
LOCAL_SRC_FILES := video-native.c
LOCAL_CFLAGS    := -O2 -Wall -fvisibility=hidden
LOCAL_LDFLAGS   := -Wl,--gc-sections -Wl,--as-needed
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS    := -llog -landroid -lmediandk
include $(BUILD_SHARED_LIBRARY)

ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined!)
endif

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/arm64
else
$(error Yalniz arm64-v8a destekleniyor: $(TARGET_ARCH_ABI))
endif

GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build/
include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk
# Yalniz gerekenler: udpsrc/rtp (NET), h265parse+opus (CODECS), opensles (SYS),
# jitterbuffer/queue/app (CORE). Video cozme AMediaCodec (NDK), GStreamer disinda.
GSTREAMER_PLUGINS := $(GSTREAMER_PLUGINS_CORE) \
                     $(GSTREAMER_PLUGINS_PLAYBACK) \
                     $(GSTREAMER_PLUGINS_CODECS) \
                     $(GSTREAMER_PLUGINS_NET) \
                     $(GSTREAMER_PLUGINS_SYS) \
                     $(GSTREAMER_PLUGINS_CAPTURE) \
                     $(GSTREAMER_PLUGINS_CODECS_RESTRICTED)
GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0 gstreamer-audio-1.0 gstreamer-app-1.0
include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk
