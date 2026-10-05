#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DreamPlayer", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DreamPlayer", __VA_ARGS__)

namespace {

std::atomic<bool> gResumed{false};
std::atomic<bool> gStop{false};
std::atomic<float> gAspect{16.0f / 9.0f};
std::atomic<int> gStereoMode{0};
// Clockwise quarter turns applied independently within each stereo eye region.
std::atomic<int> gVideoRotationQuarterTurns{0};
// 0 = flat screen, 1 = equirectangular VR180, 2 = equirectangular VR360.
std::atomic<int> gVideoProjection{0};
std::atomic<float> gImmersiveYawDegrees{0.0f};
std::atomic<float> gImmersivePitchDegrees{0.0f};
std::atomic<float> gLatestHeadYawDegrees{0.0f};
std::atomic<float> gLatestHeadPitchDegrees{0.0f};
std::atomic<uint64_t> gImmersiveMenuAnchorGeneration{1};
// 0 = original samples, 1 = metadata-driven managed color.
std::atomic<int> gPictureMode{1};
// 0 = SDR transfer, 1 = ST 2084/PQ, 2 = HLG.
std::atomic<int> gVideoTransfer{0};
// 0 = BT.709 primaries, 1 = BT.2020 primaries.
std::atomic<int> gVideoPrimaries{0};
std::atomic<float> gPictureBrightness{0.0f};
std::atomic<float> gPictureExposure{-0.50f};
std::atomic<float> gPictureContrast{1.0f};
std::atomic<float> gPictureSaturation{1.0f};
std::atomic<float> gPictureVibrance{0.0f};
std::atomic<float> gCinemaToneDepth{0.22f};
std::atomic<float> gCinemaMidtoneEv{0.15f};
std::atomic<float> gCinemaColorIntensity{1.10f};
std::atomic<float> gToneMapPeakNits{100.0f};
std::atomic<bool> gAdaptiveToneMapEnabled{false};
std::atomic<float> gAdaptiveToneMapMinNits{100.0f};
std::atomic<float> gAdaptiveToneMapMaxNits{600.0f};
// 0 = favor shadow visibility, 0.5 = original adaptive algorithm,
// 1 = favor highlight preservation.
std::atomic<float> gAdaptiveToneMapPriority{0.5f};
// 0 = Cinema (moderately responsive), 1 = Smooth (heavier temporal smoothing).
std::atomic<int> gAdaptiveToneMapMode{1};
std::atomic<float> gAdaptiveToneMapEffectiveNits{100.0f};
std::atomic<uint32_t> gAdaptiveToneMapResetGeneration{0};
std::atomic<float> gHlgSystemGamma{0.8459066f};
std::atomic<float> gHdrSourcePeakNits{1000.0f};
std::atomic<float> gHdrMaxFallNits{0.0f};
// 0 = legacy hard clip, 1 = luminance/hue-direction preserving compression.
std::atomic<int> gGamutMappingMode{0};
std::atomic<float> gSceneBrightness{0.5f};
std::atomic<bool> gPassthroughRequested{false};
std::atomic<bool> gMetaGlassesFovEnabled{false};
std::atomic<float> gPassthroughBrightness{1.0f};
std::atomic<bool> gPassthroughSupported{false};
std::atomic<bool> gSwapEyes{false};
std::atomic<float> gScreenX{0.0f};
std::atomic<float> gScreenY{0.0f};
std::atomic<float> gScreenZ{-2.4f};
std::atomic<float> gScreenHalfHeight{1.0f};
std::atomic<float> gScreenCurvature{0.0f};
std::atomic<bool> gHasVideo{false};
std::atomic<bool> gMenuInteractive{true};
std::atomic<bool> gImmersivePlaybackUi{false};
std::mutex gSubtitleMutex;
std::vector<uint8_t> gSubtitlePixels;
std::atomic<int> gSubtitleWidth{0};
std::atomic<int> gSubtitleHeight{0};
std::atomic<float> gSubtitleDepthMeters{0.0f};
std::atomic<bool> gSubtitleBitmapMode{false};
std::atomic<bool> gSubtitle3dEnabled{false};
std::atomic<float> gSubtitleLeft{0.0f};
std::atomic<float> gSubtitleTop{0.0f};
std::atomic<float> gSubtitleRight{1.0f};
std::atomic<float> gSubtitleBottom{1.0f};
std::atomic<float> gTextSubtitleRed{1.0f};
std::atomic<float> gTextSubtitleGreen{1.0f};
std::atomic<float> gTextSubtitleBlue{1.0f};
std::atomic<float> gTextSubtitleBrightness{1.0f};
std::atomic<float> gTextSubtitleScale{1.0f};
std::atomic<float> gTextSubtitleHorizontalPosition{0.5f};
std::atomic<float> gTextSubtitleVerticalPosition{0.0f};
bool gSubtitleDirty = false;
constexpr float kPi = 3.14159265358979323846f;
constexpr int kScreenSegments = 40;
constexpr int kImmersiveHorizontalSegments = 96;
constexpr int kImmersiveVerticalSegments = 48;
constexpr int kMaximumSurfaceVertices =
    kImmersiveHorizontalSegments * kImmersiveVerticalSegments * 6;
constexpr float kMaximumScreenEdgeAngle = 38.0f * kPi / 180.0f;
constexpr float kMaximumOverlayEdgeAngle = 50.0f * kPi / 180.0f;
constexpr int kAdaptiveAnalysisWidth = 32;
constexpr int kAdaptiveAnalysisHeight = 18;
// PlayerActivity renders menus into a 2048x1024 bitmap. Keep that texture at
// its native aspect instead of stretching it to each video's display aspect.
constexpr float kMenuAspect = 2.0f;
// Rotating a landscape movie by 90 degrees produces a genuinely narrow
// portrait surface (16:9 becomes 9:16, while scope content is narrower still).
// Keep the bounds broad enough to preserve that aspect ratio rather than
// widening the rotated picture as the former 0.8 minimum did.
constexpr float kMinimumVideoAspect = 0.25f;
constexpr float kMaximumVideoAspect = 2.4f;

struct Mat4 { float m[16]{}; };

struct Vec3 { float x = 0; float y = 0; float z = 0; };

Vec3 operator+(const Vec3& a, const Vec3& b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
Vec3 operator-(const Vec3& a, const Vec3& b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
Vec3 operator*(const Vec3& value, float scale) {
    return {value.x * scale, value.y * scale, value.z * scale};
}

float dot(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }

Vec3 cross(const Vec3& a, const Vec3& b) {
    return {
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    };
}

float length(const Vec3& value) { return std::sqrt(dot(value, value)); }

Vec3 normalized(const Vec3& value, const Vec3& fallback = {0, 0, -1}) {
    const float magnitude = length(value);
    return magnitude > 0.00001f ? value * (1.0f / magnitude) : fallback;
}

float normalizedAngleRadians(float angle) {
    angle = std::fmod(angle + kPi, 2.0f * kPi);
    if (angle < 0.0f) angle += 2.0f * kPi;
    return angle - kPi;
}

struct ScreenBasis {
    Vec3 center;
    Vec3 right;
    Vec3 up;
    Vec3 normal;
};

ScreenBasis currentScreenBasis() {
    const Vec3 center{gScreenX.load(), gScreenY.load(), gScreenZ.load()};
    const Vec3 outward = normalized(center);
    Vec3 right = normalized(cross(outward, {0, 1, 0}), {1, 0, 0});
    // At extreme pitch, keep the horizontal axis stable instead of allowing roll.
    if (std::abs(dot(right, {0, 1, 0})) > 0.001f) right = {1, 0, 0};
    const Vec3 up = normalized(cross(right, outward), {0, 1, 0});
    return {center, right, up, outward * -1.0f};
}

Vec3 screenPoint(const ScreenBasis& basis, float horizontal, float vertical, float forward = 0.0f) {
    return basis.center + basis.right * horizontal + basis.up * vertical + basis.normal * forward;
}

struct CurvedScreenCoordinate {
    float horizontal;
    float forward;
};

CurvedScreenCoordinate curvedScreenCoordinate(
    float halfWidth, float normalizedHorizontal, float curvature) {
    const float edgeAngle =
        std::clamp(curvature, 0.0f, 1.0f) * kMaximumScreenEdgeAngle;
    if (edgeAngle < 0.0001f) {
        return {halfWidth * normalizedHorizontal, 0.0f};
    }
    const float radius = halfWidth / edgeAngle;
    const float angle = normalizedHorizontal * edgeAngle;
    return {
        radius * std::sin(angle),
        radius * (1.0f - std::cos(angle)),
    };
}

Vec3 curvedScreenPoint(
    const ScreenBasis& basis,
    float halfWidth,
    float normalizedHorizontal,
    float vertical,
    float curvature,
    float forwardOffset = 0.0f) {
    const CurvedScreenCoordinate coordinate =
        curvedScreenCoordinate(halfWidth, normalizedHorizontal, curvature);
    return screenPoint(
        basis,
        coordinate.horizontal,
        vertical,
        coordinate.forward + forwardOffset);
}

Vec3 sharedCurvedScreenPoint(
    const ScreenBasis& basis,
    float referenceHalfWidth,
    float surfaceHalfWidth,
    float normalizedHorizontal,
    float vertical,
    float curvature,
    float forwardOffset = 0.0f) {
    const float edgeAngle =
        std::clamp(curvature, 0.0f, 1.0f) * kMaximumScreenEdgeAngle;
    if (edgeAngle < 0.0001f) {
        return screenPoint(
            basis,
            surfaceHalfWidth * normalizedHorizontal,
            vertical,
            forwardOffset);
    }
    const float radius = referenceHalfWidth / edgeAngle;
    const float arcHorizontal = surfaceHalfWidth * normalizedHorizontal;
    const float angle = arcHorizontal / radius;
    return screenPoint(
        basis,
        radius * std::sin(angle),
        vertical,
        radius * (1.0f - std::cos(angle)) + forwardOffset);
}

float overlayCurvatureReferenceHalfWidth(
    float videoHalfWidth, float overlayHalfWidth) {
    return std::max(
        videoHalfWidth,
        overlayHalfWidth *
            (kMaximumScreenEdgeAngle / kMaximumOverlayEdgeAngle));
}

bool intersectRayWithCurvedScreen(
    const Vec3& origin,
    const Vec3& direction,
    const ScreenBasis& basis,
    float surfaceHalfWidth,
    float referenceHalfWidth,
    float halfHeight,
    float curvature,
    float forwardOffset,
    Vec3* hit,
    float* u,
    float* v) {
    const Vec3 ray = normalized(direction);
    const Vec3 surfaceCenter = basis.center + basis.normal * forwardOffset;
    const Vec3 localOrigin = origin - surfaceCenter;
    const float originHorizontal = dot(localOrigin, basis.right);
    const float originVertical = dot(localOrigin, basis.up);
    const float originForward = dot(localOrigin, basis.normal);
    const float directionHorizontal = dot(ray, basis.right);
    const float directionVertical = dot(ray, basis.up);
    const float directionForward = dot(ray, basis.normal);
    const float edgeAngle =
        std::clamp(curvature, 0.0f, 1.0f) * kMaximumScreenEdgeAngle;

    if (edgeAngle < 0.0001f) {
        if (std::abs(directionForward) < 0.001f) return false;
        const float distance = -originForward / directionForward;
        if (distance <= 0.0f) return false;
        const float horizontal =
            originHorizontal + directionHorizontal * distance;
        const float vertical = originVertical + directionVertical * distance;
        if (
            std::abs(horizontal) > surfaceHalfWidth ||
            std::abs(vertical) > halfHeight) {
            return false;
        }
        *hit = origin + ray * distance;
        *u = (horizontal + surfaceHalfWidth) / (2.0f * surfaceHalfWidth);
        *v = (halfHeight - vertical) / (2.0f * halfHeight);
        return true;
    }

    const float radius = referenceHalfWidth / edgeAngle;
    const float surfaceEdgeAngle = surfaceHalfWidth / radius;
    const float shiftedForward = originForward - radius;
    const float a =
        directionHorizontal * directionHorizontal +
        directionForward * directionForward;
    if (a < 0.000001f) return false;
    const float b = 2.0f * (
        originHorizontal * directionHorizontal +
        shiftedForward * directionForward);
    const float c =
        originHorizontal * originHorizontal +
        shiftedForward * shiftedForward -
        radius * radius;
    const float discriminant = b * b - 4.0f * a * c;
    if (discriminant < 0.0f) return false;
    const float root = std::sqrt(discriminant);
    float distances[2] = {
        (-b - root) / (2.0f * a),
        (-b + root) / (2.0f * a),
    };
    if (distances[0] > distances[1]) std::swap(distances[0], distances[1]);
    for (float distance : distances) {
        if (distance <= 0.0f) continue;
        const float horizontal =
            originHorizontal + directionHorizontal * distance;
        const float vertical = originVertical + directionVertical * distance;
        const float forward =
            originForward + directionForward * distance;
        const float angle = std::atan2(horizontal, radius - forward);
        if (
            std::abs(angle) > surfaceEdgeAngle ||
            std::abs(vertical) > halfHeight) continue;
        *hit = origin + ray * distance;
        *u = (angle / surfaceEdgeAngle + 1.0f) * 0.5f;
        *v = (halfHeight - vertical) / (2.0f * halfHeight);
        return true;
    }
    return false;
}

bool intersectRayWithSphere(
    const Vec3& origin, const Vec3& direction, float radius, Vec3* result) {
    const Vec3 ray = normalized(direction);
    const float b = dot(origin, ray);
    const float c = dot(origin, origin) - radius * radius;
    const float discriminant = b * b - c;
    if (discriminant < 0.0f) return false;
    const float root = std::sqrt(discriminant);
    float distance = -b + root;
    if (distance <= 0.0f) distance = -b - root;
    if (distance <= 0.0f) return false;
    *result = origin + ray * distance;
    return true;
}

Vec3 rotateVector(const XrQuaternionf& q, const Vec3& v) {
    const Vec3 u{q.x, q.y, q.z};
    const float dot = u.x * v.x + u.y * v.y + u.z * v.z;
    const Vec3 cross{
        u.y * v.z - u.z * v.y,
        u.z * v.x - u.x * v.z,
        u.x * v.y - u.y * v.x,
    };
    return {
        2.0f * dot * u.x + (q.w * q.w - (u.x * u.x + u.y * u.y + u.z * u.z)) * v.x + 2.0f * q.w * cross.x,
        2.0f * dot * u.y + (q.w * q.w - (u.x * u.x + u.y * u.y + u.z * u.z)) * v.y + 2.0f * q.w * cross.y,
        2.0f * dot * u.z + (q.w * q.w - (u.x * u.x + u.y * u.y + u.z * u.z)) * v.z + 2.0f * q.w * cross.z,
    };
}

Mat4 identity() {
    Mat4 out{};
    out.m[0] = out.m[5] = out.m[10] = out.m[15] = 1.0f;
    return out;
}

Mat4 multiply(const Mat4& a, const Mat4& b) {
    Mat4 out{};
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            for (int k = 0; k < 4; ++k) {
                out.m[col * 4 + row] += a.m[k * 4 + row] * b.m[col * 4 + k];
            }
        }
    }
    return out;
}

Mat4 projection(const XrFovf& fov, float nearZ = 0.05f, float farZ = 100.0f) {
    const float left = std::tan(fov.angleLeft);
    const float right = std::tan(fov.angleRight);
    const float down = std::tan(fov.angleDown);
    const float up = std::tan(fov.angleUp);
    Mat4 out{};
    out.m[0] = 2.0f / (right - left);
    out.m[5] = 2.0f / (up - down);
    out.m[8] = (right + left) / (right - left);
    out.m[9] = (up + down) / (up - down);
    out.m[10] = -(farZ + nearZ) / (farZ - nearZ);
    out.m[11] = -1.0f;
    out.m[14] = -(2.0f * farZ * nearZ) / (farZ - nearZ);
    return out;
}

Mat4 viewFromPose(const XrPosef& pose) {
    const float x = -pose.orientation.x;
    const float y = -pose.orientation.y;
    const float z = -pose.orientation.z;
    const float w = pose.orientation.w;
    Mat4 rotation = identity();
    rotation.m[0] = 1 - 2 * y * y - 2 * z * z;
    rotation.m[1] = 2 * x * y + 2 * w * z;
    rotation.m[2] = 2 * x * z - 2 * w * y;
    rotation.m[4] = 2 * x * y - 2 * w * z;
    rotation.m[5] = 1 - 2 * x * x - 2 * z * z;
    rotation.m[6] = 2 * y * z + 2 * w * x;
    rotation.m[8] = 2 * x * z + 2 * w * y;
    rotation.m[9] = 2 * y * z - 2 * w * x;
    rotation.m[10] = 1 - 2 * x * x - 2 * y * y;
    Mat4 translation = identity();
    translation.m[12] = -pose.position.x;
    translation.m[13] = -pose.position.y;
    translation.m[14] = -pose.position.z;
    return multiply(rotation, translation);
}

void checkXr(XrResult result, const char* operation) {
    if (XR_FAILED(result)) throw std::runtime_error(std::string(operation) + " failed: " + std::to_string(result));
}

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024]{};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        glDeleteShader(shader);
        throw std::runtime_error(std::string("Shader compilation failed: ") + log);
    }
    return shader;
}

GLuint makeProgram(const char* vertex, const char* fragment) {
    GLuint vs = 0;
    GLuint fs = 0;
    GLuint program = 0;
    try {
        vs = compileShader(GL_VERTEX_SHADER, vertex);
        fs = compileShader(GL_FRAGMENT_SHADER, fragment);
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        GLint ok = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &ok);
        if (!ok) {
            char log[1024]{};
            glGetProgramInfoLog(program, sizeof(log), nullptr, log);
            throw std::runtime_error(std::string("Program link failed: ") + log);
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        return program;
    } catch (...) {
        if (program) glDeleteProgram(program);
        if (fs) glDeleteShader(fs);
        if (vs) glDeleteShader(vs);
        throw;
    }
}

struct EglState {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;

    void create() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
            throw std::runtime_error("Unable to initialize EGL");
        }
        const EGLint configAttributes[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_NONE,
        };
        EGLint count = 0;
        if (!eglChooseConfig(display, configAttributes, &config, 1, &count) || count == 0) {
            throw std::runtime_error("No suitable GLES3 EGL config");
        }
        const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
        const EGLint surfaceAttributes[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
        surface = eglCreatePbufferSurface(display, config, surfaceAttributes);
        if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE ||
            !eglMakeCurrent(display, surface, surface, context)) {
            throw std::runtime_error("Unable to create EGL context");
        }
    }

    void destroy() {
        if (display == EGL_NO_DISPLAY) return;
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
        config = nullptr;
        context = EGL_NO_CONTEXT;
        surface = EGL_NO_SURFACE;
    }
};

struct JavaVideoSurface {
    JavaVM* vm = nullptr;
    jobject activity = nullptr;
    jobject surfaceTexture = nullptr;
    jobject surface = nullptr;
    jmethodID updateTexImage = nullptr;
    jmethodID getTransformMatrix = nullptr;
    jmethodID getTimestamp = nullptr;
    jmethodID releaseTexture = nullptr;
    jmethodID releaseSurface = nullptr;

    void create(JNIEnv* env, jobject sourceActivity, GLuint texture) {
        env->GetJavaVM(&vm);
        activity = env->NewGlobalRef(sourceActivity);
        jclass textureClass = env->FindClass("android/graphics/SurfaceTexture");
        jmethodID textureCtor = env->GetMethodID(textureClass, "<init>", "(I)V");
        jobject localTexture = env->NewObject(textureClass, textureCtor, static_cast<jint>(texture));
        surfaceTexture = env->NewGlobalRef(localTexture);
        updateTexImage = env->GetMethodID(textureClass, "updateTexImage", "()V");
        getTransformMatrix = env->GetMethodID(textureClass, "getTransformMatrix", "([F)V");
        getTimestamp = env->GetMethodID(textureClass, "getTimestamp", "()J");
        releaseTexture = env->GetMethodID(textureClass, "release", "()V");

        jclass surfaceClass = env->FindClass("android/view/Surface");
        jmethodID surfaceCtor = env->GetMethodID(
            surfaceClass, "<init>", "(Landroid/graphics/SurfaceTexture;)V");
        jobject localSurface = env->NewObject(surfaceClass, surfaceCtor, localTexture);
        surface = env->NewGlobalRef(localSurface);
        releaseSurface = env->GetMethodID(surfaceClass, "release", "()V");

        jclass activityClass = env->GetObjectClass(sourceActivity);
        jmethodID callback = env->GetMethodID(
            activityClass, "onNativeVideoSurface", "(Landroid/view/Surface;)V");
        env->CallVoidMethod(sourceActivity, callback, localSurface);
        env->DeleteLocalRef(localSurface);
        env->DeleteLocalRef(localTexture);
        env->DeleteLocalRef(surfaceClass);
        env->DeleteLocalRef(textureClass);
    }

    bool update(JNIEnv* env, float* matrix, int64_t* timestampNanos) {
        if (!surfaceTexture) return false;
        env->ExceptionClear();
        env->CallVoidMethod(surfaceTexture, updateTexImage);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        jfloatArray values = env->NewFloatArray(16);
        env->CallVoidMethod(surfaceTexture, getTransformMatrix, values);
        env->GetFloatArrayRegion(values, 0, 16, matrix);
        env->DeleteLocalRef(values);
        if (timestampNanos && getTimestamp) {
            *timestampNanos = static_cast<int64_t>(
                env->CallLongMethod(surfaceTexture, getTimestamp));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                *timestampNanos = 0;
            }
        }
        return true;
    }

    void notifyExit(JNIEnv* env, const std::string& reason) {
        if (!activity) return;
        jclass cls = env->GetObjectClass(activity);
        jmethodID callback = env->GetMethodID(cls, "onNativeExit", "(Ljava/lang/String;)V");
        jstring text = env->NewStringUTF(reason.c_str());
        env->CallVoidMethod(activity, callback, text);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(cls);
    }

    void destroy(JNIEnv* env) {
        if (surface && releaseSurface) env->CallVoidMethod(surface, releaseSurface);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (surfaceTexture && releaseTexture) env->CallVoidMethod(surfaceTexture, releaseTexture);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (surface) env->DeleteGlobalRef(surface);
        if (surfaceTexture) env->DeleteGlobalRef(surfaceTexture);
        if (activity) env->DeleteGlobalRef(activity);
        surface = surfaceTexture = activity = nullptr;
        vm = nullptr;
        updateTexImage = getTransformMatrix = getTimestamp =
            releaseTexture = releaseSurface = nullptr;
    }
};

struct Swapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    int32_t width = 0;
    int32_t height = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
};

class Renderer {
public:
    Renderer(JNIEnv* env, jobject activity, int stereoMode, float renderResolutionScale)
        : env_(env),
          activity_(activity),
          renderResolutionScale_(
                  std::isfinite(renderResolutionScale)
                      ? std::clamp(renderResolutionScale, 1.0f, 1.6f)
                      : 1.0f) {
        gStereoMode.store(stereoMode);
        jclass cls = env_->GetObjectClass(activity_);
        frameCallback_ = env_->GetMethodID(cls, "onNativeFrame", "(J)V");
        seekCallback_ = env_->GetMethodID(cls, "onNativeSeekGesture", "(IFF)V");
        zoomCallback_ = env_->GetMethodID(cls, "onNativeZoomGesture", "(FFF)V");
        immersiveOrientationCallback_ = env_->GetMethodID(
            cls,
            "onNativeImmersiveOrientationChanged",
            "(FF)V");
        env_->DeleteLocalRef(cls);
    }

    ~Renderer() { shutdown(); }

    void run() {
        egl_.create();
        initializeOpenXr();
        initializeGraphics();
        videoSurface_.create(env_, activity_, videoTexture_);
        loop();
    }

private:
    JNIEnv* env_;
    jobject activity_;
    jmethodID frameCallback_ = nullptr;
    jmethodID seekCallback_ = nullptr;
    jmethodID zoomCallback_ = nullptr;
    jmethodID immersiveOrientationCallback_ = nullptr;
    float renderResolutionScale_ = 1.0f;
    EglState egl_;
    JavaVideoSurface videoSurface_;
    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId systemId_ = XR_NULL_SYSTEM_ID;
    XrSession session_ = XR_NULL_HANDLE;
    XrPassthroughFB passthrough_ = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer_ = XR_NULL_HANDLE;
    PFN_xrCreatePassthroughFB createPassthrough_ = nullptr;
    PFN_xrDestroyPassthroughFB destroyPassthrough_ = nullptr;
    PFN_xrPassthroughStartFB startPassthrough_ = nullptr;
    PFN_xrPassthroughPauseFB pausePassthrough_ = nullptr;
    PFN_xrCreatePassthroughLayerFB createPassthroughLayer_ = nullptr;
    PFN_xrDestroyPassthroughLayerFB destroyPassthroughLayer_ = nullptr;
    PFN_xrPassthroughLayerPauseFB pausePassthroughLayer_ = nullptr;
    PFN_xrPassthroughLayerResumeFB resumePassthroughLayer_ = nullptr;
    PFN_xrPassthroughLayerSetStyleFB setPassthroughStyle_ = nullptr;
    bool passthroughExtensionAvailable_ = false;
    bool alphaBlendSupported_ = false;
    bool fbPassthroughUsable_ = false;
    bool passthroughRunning_ = false;
    XrSpace space_ = XR_NULL_HANDLE;
    XrActionSet actionSet_ = XR_NULL_HANDLE;
    XrAction aimAction_ = XR_NULL_HANDLE;
    XrAction selectAction_ = XR_NULL_HANDLE;
    XrAction thumbstickAction_ = XR_NULL_HANDLE;
    std::array<XrPath, 2> handPaths_{};
    std::array<XrSpace, 2> aimSpaces_{};
    std::array<bool, 2> selectDown_{};
    int menuPointerHand_ = -1;
    float lastMenuPointerU_ = 0.5f;
    float lastMenuPointerV_ = 0.5f;
    XrSessionState sessionState_ = XR_SESSION_STATE_UNKNOWN;
    bool sessionRunning_ = false;
    std::vector<XrViewConfigurationView> viewConfigs_;
    std::vector<XrView> views_;
    std::vector<Swapchain> swapchains_;
    GLuint framebuffer_ = 0;
    GLuint adaptiveAnalysisFramebuffer_ = 0;
    GLuint adaptiveAnalysisTexture_ = 0;
    GLuint videoTexture_ = 0;
    GLuint subtitleTexture_ = 0;
    int subtitleTextureWidth_ = 0;
    int subtitleTextureHeight_ = 0;
    GLuint videoProgram_ = 0;
    GLuint adaptiveAnalysisProgram_ = 0;
    GLuint subtitleProgram_ = 0;
    GLuint pointerProgram_ = 0;
    GLuint vertexBuffer_ = 0;
    GLuint vertexArray_ = 0;
    float textureTransform_[16]{};
    bool pointerVisible_ = false;
    Vec3 pointerOrigin_{};
    Vec3 pointerHit_{};
    Vec3 headCenter_{};
    ScreenBasis headRelativeMenuBasis_{
        {0.0f, 0.0f, -2.0f},
        {1.0f, 0.0f, 0.0f},
        {0.0f, 1.0f, 0.0f},
        {0.0f, 0.0f, 1.0f},
    };
    ScreenBasis anchoredImmersiveMenuBasis_{
        {0.0f, 0.0f, -2.0f},
        {1.0f, 0.0f, 0.0f},
        {0.0f, 1.0f, 0.0f},
        {0.0f, 0.0f, 1.0f},
    };
    uint64_t immersiveMenuAnchorGeneration_ = 0;
    XrTime lastPointerCallback_ = 0;
    XrTime lastScrollCallback_ = 0;
    float lastScrollValue_ = 0.0f;
    int horizontalSeekDirection_ = 0;
    XrTime horizontalSeekStarted_ = 0;
    XrTime lastHorizontalSeekCallback_ = 0;
    XrTime lastZoomCallback_ = 0;
    int draggingHand_ = -1;
    float dragYawOffset_ = 0.0f;
    float dragPitchOffset_ = 0.0f;
    float dragRadius_ = 2.4f;
    Vec3 dragStartCenter_{};
    bool dragMoved_ = false;
    bool draggingImmersive_ = false;
    float immersiveDragStartPointerYaw_ = 0.0f;
    float immersiveDragStartVideoYaw_ = 0.0f;
    bool shutdownComplete_ = false;
    int64_t lastAnalyzedVideoTimestampNanos_ = 0;
    uint32_t adaptiveResetGeneration_ = 0;
    bool adaptiveHistoryValid_ = false;
    float adaptiveEffectiveTargetNits_ = 100.0f;
    std::array<float, kAdaptiveAnalysisWidth * kAdaptiveAnalysisHeight>
        previousAdaptiveLogLuma_{};
    std::array<uint8_t, kAdaptiveAnalysisWidth * kAdaptiveAnalysisHeight * 4>
        adaptiveReadback_{};

    void initializeOpenXr() {
        PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
        checkXr(xrGetInstanceProcAddr(
            XR_NULL_HANDLE,
            "xrInitializeLoaderKHR",
            reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)),
            "xrGetInstanceProcAddr(xrInitializeLoaderKHR)");
        JavaVM* vm = nullptr;
        env_->GetJavaVM(&vm);
        XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loaderInfo.applicationVM = vm;
        loaderInfo.applicationContext = activity_;
        checkXr(initializeLoader(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)),
                "xrInitializeLoaderKHR");

        uint32_t extensionCount = 0;
        checkXr(
            xrEnumerateInstanceExtensionProperties(
                nullptr, 0, &extensionCount, nullptr),
            "xrEnumerateInstanceExtensionProperties(count)");
        std::vector<XrExtensionProperties> availableExtensions(
            extensionCount, {XR_TYPE_EXTENSION_PROPERTIES});
        checkXr(
            xrEnumerateInstanceExtensionProperties(
                nullptr,
                extensionCount,
                &extensionCount,
                availableExtensions.data()),
            "xrEnumerateInstanceExtensionProperties(list)");
        passthroughExtensionAvailable_ = std::any_of(
            availableExtensions.begin(), availableExtensions.end(),
            [](const XrExtensionProperties& extension) {
                return std::strcmp(
                    extension.extensionName,
                    XR_FB_PASSTHROUGH_EXTENSION_NAME) == 0;
            });
        std::vector<const char*> extensions{
            XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
            XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
        };
        if (passthroughExtensionAvailable_) {
            extensions.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);
        }
        XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        androidInfo.applicationVM = vm;
        androidInfo.applicationActivity = activity_;
        XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
        createInfo.next = &androidInfo;
        std::strncpy(createInfo.applicationInfo.applicationName, "Dream Player",
                     XR_MAX_APPLICATION_NAME_SIZE - 1);
        createInfo.applicationInfo.applicationVersion = 1;
        std::strncpy(createInfo.applicationInfo.engineName, "CleanRoom",
                     XR_MAX_ENGINE_NAME_SIZE - 1);
        createInfo.applicationInfo.engineVersion = 1;
        createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;
        createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
        createInfo.enabledExtensionNames = extensions.data();
        checkXr(xrCreateInstance(&createInfo, &instance_), "xrCreateInstance");

        XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
        systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        checkXr(xrGetSystem(instance_, &systemInfo, &systemId_), "xrGetSystem");

        uint32_t blendModeCount = 0;
        checkXr(
            xrEnumerateEnvironmentBlendModes(
                instance_,
                systemId_,
                XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                0,
                &blendModeCount,
                nullptr),
            "xrEnumerateEnvironmentBlendModes(count)");
        std::vector<XrEnvironmentBlendMode> blendModes(blendModeCount);
        checkXr(
            xrEnumerateEnvironmentBlendModes(
                instance_,
                systemId_,
                XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                blendModeCount,
                &blendModeCount,
                blendModes.data()),
            "xrEnumerateEnvironmentBlendModes(list)");
        alphaBlendSupported_ = std::find(
            blendModes.begin(), blendModes.end(),
            XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) != blendModes.end();

        PFN_xrGetOpenGLESGraphicsRequirementsKHR requirementsFn = nullptr;
        checkXr(xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
            reinterpret_cast<PFN_xrVoidFunction*>(&requirementsFn)), "get GLES requirements function");
        XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        checkXr(requirementsFn(instance_, systemId_, &requirements), "xrGetOpenGLESGraphicsRequirementsKHR");

        XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        graphicsBinding.display = egl_.display;
        graphicsBinding.config = egl_.config;
        graphicsBinding.context = egl_.context;
        XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO};
        sessionInfo.next = &graphicsBinding;
        sessionInfo.systemId = systemId_;
        checkXr(xrCreateSession(instance_, &sessionInfo, &session_), "xrCreateSession");
        initializePassthrough();

        XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
        checkXr(xrCreateReferenceSpace(session_, &spaceInfo, &space_), "xrCreateReferenceSpace");
        initializeActions();

        uint32_t count = 0;
        checkXr(xrEnumerateViewConfigurationViews(instance_, systemId_,
            XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count, nullptr), "enumerate view count");
        viewConfigs_.assign(count, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
        checkXr(xrEnumerateViewConfigurationViews(instance_, systemId_,
            XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, &count, viewConfigs_.data()),
            "enumerate views");
        views_.assign(count, {XR_TYPE_VIEW});
        createSwapchains();
    }

    template <typename Function>
    bool loadOptionalFunction(const char* name, Function& function) {
        return XR_SUCCEEDED(xrGetInstanceProcAddr(
            instance_, name,
            reinterpret_cast<PFN_xrVoidFunction*>(&function))) && function != nullptr;
    }

    void initializePassthrough() {
        gPassthroughSupported.store(false);
        if (!passthroughExtensionAvailable_) {
            gPassthroughSupported.store(alphaBlendSupported_);
            return;
        }
        const bool loaded =
            loadOptionalFunction("xrCreatePassthroughFB", createPassthrough_) &&
            loadOptionalFunction("xrDestroyPassthroughFB", destroyPassthrough_) &&
            loadOptionalFunction("xrPassthroughStartFB", startPassthrough_) &&
            loadOptionalFunction("xrPassthroughPauseFB", pausePassthrough_) &&
            loadOptionalFunction("xrCreatePassthroughLayerFB", createPassthroughLayer_) &&
            loadOptionalFunction("xrDestroyPassthroughLayerFB", destroyPassthroughLayer_) &&
            loadOptionalFunction("xrPassthroughLayerPauseFB", pausePassthroughLayer_) &&
            loadOptionalFunction("xrPassthroughLayerResumeFB", resumePassthroughLayer_) &&
            loadOptionalFunction("xrPassthroughLayerSetStyleFB", setPassthroughStyle_);
        if (!loaded) {
            LOGE("OpenXR passthrough functions are incomplete; using alpha fallback");
            gPassthroughSupported.store(alphaBlendSupported_);
            return;
        }

        XrSystemPassthroughPropertiesFB passthroughProperties{
            XR_TYPE_SYSTEM_PASSTHROUGH_PROPERTIES_FB};
        XrSystemProperties systemProperties{XR_TYPE_SYSTEM_PROPERTIES};
        systemProperties.next = &passthroughProperties;
        const XrResult propertiesResult = xrGetSystemProperties(
            instance_, systemId_, &systemProperties);
        if (XR_FAILED(propertiesResult) || !passthroughProperties.supportsPassthrough) {
            LOGE("OpenXR runtime reports passthrough unavailable; using alpha fallback");
            gPassthroughSupported.store(alphaBlendSupported_);
            return;
        }

        XrPassthroughCreateInfoFB passthroughInfo{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
        XrResult result = createPassthrough_(session_, &passthroughInfo, &passthrough_);
        if (XR_FAILED(result)) {
            LOGE("xrCreatePassthroughFB failed: %d", result);
            passthrough_ = XR_NULL_HANDLE;
            gPassthroughSupported.store(alphaBlendSupported_);
            return;
        }
        XrPassthroughLayerCreateInfoFB layerInfo{
            XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
        layerInfo.passthrough = passthrough_;
        layerInfo.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
        result = createPassthroughLayer_(session_, &layerInfo, &passthroughLayer_);
        if (XR_FAILED(result)) {
            LOGE("xrCreatePassthroughLayerFB failed: %d", result);
            destroyPassthrough_(passthrough_);
            passthrough_ = XR_NULL_HANDLE;
            passthroughLayer_ = XR_NULL_HANDLE;
            gPassthroughSupported.store(alphaBlendSupported_);
            return;
        }
        XrPassthroughStyleFB style{XR_TYPE_PASSTHROUGH_STYLE_FB};
        style.textureOpacityFactor = 1.0f;
        style.edgeColor = {0.0f, 0.0f, 0.0f, 0.0f};
        setPassthroughStyle_(passthroughLayer_, &style);
        fbPassthroughUsable_ = true;
        gPassthroughSupported.store(true);
        LOGI("OpenXR passthrough initialized");
    }

    bool updatePassthroughState() {
        const bool requested = gPassthroughRequested.load() && gPassthroughSupported.load();
        if (!fbPassthroughUsable_ || !passthrough_ || !passthroughLayer_) {
            return requested && alphaBlendSupported_;
        }
        if (requested == passthroughRunning_) return requested;
        XrResult result = XR_SUCCESS;
        if (requested) {
            result = startPassthrough_(passthrough_);
            if (XR_SUCCEEDED(result)) result = resumePassthroughLayer_(passthroughLayer_);
        } else {
            result = pausePassthroughLayer_(passthroughLayer_);
            const XrResult pauseResult = pausePassthrough_(passthrough_);
            if (XR_SUCCEEDED(result)) result = pauseResult;
        }
        if (XR_FAILED(result)) {
            LOGE("OpenXR passthrough state change failed: %d", result);
            if (requested) {
                fbPassthroughUsable_ = false;
                gPassthroughSupported.store(alphaBlendSupported_);
                return alphaBlendSupported_;
            }
        } else {
            passthroughRunning_ = requested;
        }
        return requested && (passthroughRunning_ || alphaBlendSupported_);
    }

    XrPath path(const char* value) {
        XrPath result = XR_NULL_PATH;
        checkXr(xrStringToPath(instance_, value, &result), value);
        return result;
    }

    void suggestBindings(const char* profile, const char* selectPath, const char* thumbstickPath = nullptr) {
        const XrPath profilePath = path(profile);
        std::vector<XrActionSuggestedBinding> bindings{
            {aimAction_, path("/user/hand/left/input/aim/pose")},
            {aimAction_, path("/user/hand/right/input/aim/pose")},
            {selectAction_, path((std::string("/user/hand/left") + selectPath).c_str())},
            {selectAction_, path((std::string("/user/hand/right") + selectPath).c_str())},
        };
        if (thumbstickPath) {
            bindings.push_back({thumbstickAction_, path((std::string("/user/hand/left") + thumbstickPath).c_str())});
            bindings.push_back({thumbstickAction_, path((std::string("/user/hand/right") + thumbstickPath).c_str())});
        }
        XrInteractionProfileSuggestedBinding info{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        info.interactionProfile = profilePath;
        info.suggestedBindings = bindings.data();
        info.countSuggestedBindings = static_cast<uint32_t>(bindings.size());
        const XrResult result = xrSuggestInteractionProfileBindings(instance_, &info);
        if (XR_FAILED(result)) LOGI("Interaction profile not available: %s (%d)", profile, result);
    }

    void initializeActions() {
        handPaths_[0] = path("/user/hand/left");
        handPaths_[1] = path("/user/hand/right");

        XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
        std::strncpy(setInfo.actionSetName, "dreamplayer_ui", XR_MAX_ACTION_SET_NAME_SIZE - 1);
        std::strncpy(setInfo.localizedActionSetName, "Dream Player UI", XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1);
        setInfo.priority = 0;
        checkXr(xrCreateActionSet(instance_, &setInfo, &actionSet_), "xrCreateActionSet");

        XrActionCreateInfo aimInfo{XR_TYPE_ACTION_CREATE_INFO};
        aimInfo.actionType = XR_ACTION_TYPE_POSE_INPUT;
        std::strncpy(aimInfo.actionName, "aim_pose", XR_MAX_ACTION_NAME_SIZE - 1);
        std::strncpy(aimInfo.localizedActionName, "Aim pose", XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
        aimInfo.countSubactionPaths = 2;
        aimInfo.subactionPaths = handPaths_.data();
        checkXr(xrCreateAction(actionSet_, &aimInfo, &aimAction_), "xrCreateAction(aim)");

        XrActionCreateInfo selectInfo{XR_TYPE_ACTION_CREATE_INFO};
        selectInfo.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
        std::strncpy(selectInfo.actionName, "select", XR_MAX_ACTION_NAME_SIZE - 1);
        std::strncpy(selectInfo.localizedActionName, "Select", XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
        selectInfo.countSubactionPaths = 2;
        selectInfo.subactionPaths = handPaths_.data();
        checkXr(xrCreateAction(actionSet_, &selectInfo, &selectAction_), "xrCreateAction(select)");

        XrActionCreateInfo thumbstickInfo{XR_TYPE_ACTION_CREATE_INFO};
        thumbstickInfo.actionType = XR_ACTION_TYPE_VECTOR2F_INPUT;
        std::strncpy(thumbstickInfo.actionName, "screen_size", XR_MAX_ACTION_NAME_SIZE - 1);
        std::strncpy(thumbstickInfo.localizedActionName, "Screen size", XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
        thumbstickInfo.countSubactionPaths = 2;
        thumbstickInfo.subactionPaths = handPaths_.data();
        checkXr(xrCreateAction(actionSet_, &thumbstickInfo, &thumbstickAction_), "xrCreateAction(thumbstick)");

        suggestBindings("/interaction_profiles/khr/simple_controller", "/input/select/click");
        suggestBindings("/interaction_profiles/oculus/touch_controller", "/input/trigger/value", "/input/thumbstick");
        suggestBindings("/interaction_profiles/htc/vive_controller", "/input/trigger/click", "/input/trackpad");
        suggestBindings("/interaction_profiles/microsoft/motion_controller", "/input/trigger/value", "/input/thumbstick");

        XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
        attach.countActionSets = 1;
        attach.actionSets = &actionSet_;
        checkXr(xrAttachSessionActionSets(session_, &attach), "xrAttachSessionActionSets");

        for (size_t hand = 0; hand < aimSpaces_.size(); ++hand) {
            XrActionSpaceCreateInfo spaceInfo{XR_TYPE_ACTION_SPACE_CREATE_INFO};
            spaceInfo.action = aimAction_;
            spaceInfo.subactionPath = handPaths_[hand];
            spaceInfo.poseInActionSpace.orientation.w = 1.0f;
            checkXr(xrCreateActionSpace(session_, &spaceInfo, &aimSpaces_[hand]), "xrCreateActionSpace");
        }
    }

    void createSwapchains() {
        uint32_t formatCount = 0;
        checkXr(xrEnumerateSwapchainFormats(session_, 0, &formatCount, nullptr), "swapchain formats count");
        std::vector<int64_t> formats(formatCount);
        checkXr(xrEnumerateSwapchainFormats(session_, formatCount, &formatCount, formats.data()),
                "swapchain formats");
        int64_t format = formats.front();
        for (int64_t candidate : formats) {
            if (candidate == GL_SRGB8_ALPHA8) { format = candidate; break; }
            if (candidate == GL_RGBA8) format = candidate;
        }
        std::string formatList;
        for (int64_t candidate : formats) {
            if (!formatList.empty()) formatList += ", ";
            formatList += std::to_string(candidate);
        }
        LOGI("OpenXR swapchain formats: [%s], selected=%lld%s",
             formatList.c_str(), static_cast<long long>(format),
             format == GL_SRGB8_ALPHA8 ? " (GL_SRGB8_ALPHA8)" :
             format == GL_RGBA8 ? " (GL_RGBA8)" : "");
        swapchains_.resize(viewConfigs_.size());
        float effectiveScale = renderResolutionScale_;
        for (const auto& viewConfig : viewConfigs_) {
            const float maximumWidthScale =
                    static_cast<float>(viewConfig.maxImageRectWidth) /
                    static_cast<float>(viewConfig.recommendedImageRectWidth);
            const float maximumHeightScale =
                    static_cast<float>(viewConfig.maxImageRectHeight) /
                    static_cast<float>(viewConfig.recommendedImageRectHeight);
            effectiveScale = std::min(
                    effectiveScale,
                    std::min(maximumWidthScale, maximumHeightScale));
        }
        effectiveScale = std::max(1.0f, effectiveScale);
        LOGI("OpenXR render resolution scale requested=%.2f effective=%.2f",
             renderResolutionScale_, effectiveScale);
        for (size_t i = 0; i < swapchains_.size(); ++i) {
            auto& chain = swapchains_[i];
            chain.width = std::min(
                    static_cast<int32_t>(viewConfigs_[i].maxImageRectWidth),
                    static_cast<int32_t>(std::lround(
                            static_cast<float>(viewConfigs_[i].recommendedImageRectWidth) *
                            effectiveScale)));
            chain.height = std::min(
                    static_cast<int32_t>(viewConfigs_[i].maxImageRectHeight),
                    static_cast<int32_t>(std::lround(
                            static_cast<float>(viewConfigs_[i].recommendedImageRectHeight) *
                            effectiveScale)));
            LOGI("OpenXR view %zu swapchain %dx%d (recommended=%ux%u, maximum=%ux%u)",
                 i, chain.width, chain.height,
                 viewConfigs_[i].recommendedImageRectWidth,
                 viewConfigs_[i].recommendedImageRectHeight,
                 viewConfigs_[i].maxImageRectWidth,
                 viewConfigs_[i].maxImageRectHeight);
            XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
            info.format = format;
            info.sampleCount = 1;
            info.width = chain.width;
            info.height = chain.height;
            info.faceCount = 1;
            info.arraySize = 1;
            info.mipCount = 1;
            checkXr(xrCreateSwapchain(session_, &info, &chain.handle), "xrCreateSwapchain");
            uint32_t imageCount = 0;
            checkXr(xrEnumerateSwapchainImages(chain.handle, 0, &imageCount, nullptr), "image count");
            chain.images.assign(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            checkXr(xrEnumerateSwapchainImages(
                chain.handle, imageCount, &imageCount,
                reinterpret_cast<XrSwapchainImageBaseHeader*>(chain.images.data())), "swapchain images");
        }
    }

    void initializeGraphics() {
        const char* vertex = R"glsl(#version 300 es
            layout(location=0) in vec3 aPosition;
            layout(location=1) in vec2 aUv;
            uniform mat4 uMvp;
            out vec2 vUv;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); vUv = aUv; }
        )glsl";
        const char* videoFragment = R"glsl(#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            in vec2 vUv;
            uniform samplerExternalOES uVideo;
            uniform mat4 uTexTransform;
            uniform int uMode;
            uniform int uEye;
            uniform int uRotationQuarterTurns;
            uniform int uPictureMode;
            uniform int uTransfer;
            uniform int uPrimaries;
            uniform float uBrightness;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uVibrance;
            uniform float uToneMapPeakNits;
            uniform float uHlgSystemGamma;
            uniform float uHdrSourcePeakNits;
            uniform float uHdrMaxFallNits;
            uniform int uGamutMappingMode;
            uniform float uCinemaToneDepth;
            uniform float uCinemaMidtoneEv;
            uniform float uCinemaColorIntensity;
            out vec4 fragColor;
            vec3 perceptualDisplayFinish(vec3 linearRgb) {
                // A low-cost perceptual-domain finishing transform. Approximate
                // display-encoded light with sqrt(). Vibrance selectively boosts
                // muted colors; Cinema then adds its tone curve and global chroma.
                vec3 perceptual = sqrt(clamp(linearRgb, 0.0, 1.0));
                const vec3 displayLuma = vec3(0.2126, 0.7152, 0.0722);
                float luma = dot(perceptual, displayLuma);
                float maximumChannel = max(
                    perceptual.r,
                    max(perceptual.g, perceptual.b));
                float minimumChannel = min(
                    perceptual.r,
                    min(perceptual.g, perceptual.b));
                float saturation = (maximumChannel - minimumChannel) /
                    max(maximumChannel, 0.0001);
                float vibranceGain = uVibrance >= 0.0
                    ? 1.0 + uVibrance *
                        (1.0 - saturation) * (1.0 - saturation)
                    : 1.0 + uVibrance;
                perceptual = mix(vec3(luma), perceptual, vibranceGain);

                luma = dot(perceptual, displayLuma);
                float curvedLuma = luma +
                    uCinemaToneDepth * luma * (1.0 - luma) * (2.0 * luma - 1.0);
                float midtoneGain = exp2(uCinemaMidtoneEv);
                curvedLuma =
                    (midtoneGain * curvedLuma) /
                    (1.0 + (midtoneGain - 1.0) * curvedLuma);
                perceptual *= curvedLuma / max(luma, 0.0001);
                float adjustedLuma = dot(perceptual, displayLuma);
                perceptual = mix(
                    vec3(adjustedLuma),
                    perceptual,
                    uCinemaColorIntensity);
                return perceptual * perceptual;
            }
            vec3 bt709ToLinear(vec3 value) {
                bvec3 low = lessThan(value, vec3(0.081));
                vec3 lowPart = value / 4.5;
                vec3 highPart = pow((value + vec3(0.099)) / 1.099, vec3(1.0 / 0.45));
                return mix(highPart, lowPart, low);
            }
            vec3 pqToNits(vec3 value) {
                const float m1 = 2610.0 / 16384.0;
                const float m2 = 2523.0 / 32.0;
                const float c1 = 3424.0 / 4096.0;
                const float c2 = 2413.0 / 128.0;
                const float c3 = 2392.0 / 128.0;
                vec3 p = pow(max(value, vec3(0.0)), vec3(1.0 / m2));
                vec3 numerator = max(p - vec3(c1), vec3(0.0));
                vec3 denominator = max(vec3(c2) - vec3(c3) * p, vec3(0.000001));
                return 10000.0 * pow(numerator / denominator, vec3(1.0 / m1));
            }
            vec3 hlgToSceneLinear(vec3 value) {
                const float a = 0.17883277;
                const float b = 0.28466892;
                const float c = 0.55991073;
                bvec3 low = lessThanEqual(value, vec3(0.5));
                vec3 lowPart = value * value / 3.0;
                vec3 highPart = (exp((value - vec3(c)) / a) + vec3(b)) / 12.0;
                return mix(highPart, lowPart, low);
            }
            vec3 bt2020ToBt709(vec3 value) {
                return mat3(
                     1.660491, -0.124550, -0.018151,
                    -0.587641,  1.132900, -0.100579,
                    -0.072850, -0.008349,  1.118730
                ) * value;
            }
            const vec3 bt709Luma = vec3(0.2126, 0.7152, 0.0722);
            const vec3 bt2020Luma = vec3(0.2627, 0.6780, 0.0593);
            float channelGamutLimit(float luma, float chroma) {
                if (chroma > 0.000001) return (1.0 - luma) / chroma;
                if (chroma < -0.000001) return luma / -chroma;
                return 10000.0;
            }
            vec3 perceptualGamutMap(vec3 value) {
                // Compress toward the BT.709 luminance axis. This keeps luminance
                // and the RGB chroma direction stable, avoiding the hue shifts and
                // flat clipped plateaus produced by per-channel hard clipping.
                float luma = clamp(dot(value, bt709Luma), 0.0, 1.0);
                vec3 chroma = value - vec3(luma);
                float limit = min(
                    channelGamutLimit(luma, chroma.r),
                    min(
                        channelGamutLimit(luma, chroma.g),
                        channelGamutLimit(luma, chroma.b)));
                if (limit >= 1000.0) return vec3(luma);
                float occupancy = 1.0 / max(limit, 0.000001);
                const float knee = 0.85;
                if (occupancy <= knee) return value;
                float mappedOccupancy = knee + (1.0 - knee) *
                    (1.0 - exp(-(occupancy - knee) / (1.0 - knee)));
                return vec3(luma) + chroma * (mappedOccupancy / occupancy);
            }
            vec3 mapOutputGamut(vec3 value, bool convertedFromWideGamut) {
                if (uGamutMappingMode == 1 && convertedFromWideGamut) {
                    return perceptualGamutMap(value);
                }
                return clamp(value, 0.0, 1.0);
            }
            vec3 pqToTargetDisplay(vec3 sourceNits, vec3 lumaCoefficients) {
                sourceNits = max(sourceNits, vec3(0.0));
                float sourceLumaNits = max(dot(sourceNits, lumaCoefficients), 0.0);
                const float hdrDiffuseWhiteNits = 203.0;
                const float diffuseWhiteFraction = 0.75;
                const float midtonePower = 0.80;
                float targetPeakNits = max(uToneMapPeakNits, 1.0);
                // Place HDR diffuse white below the display peak, leaving room
                // for specular highlights. The mild perceptual power keeps very
                // dark PQ detail visible without adding a raised-black toe.
                float targetDiffuseWhiteNits = min(
                    hdrDiffuseWhiteNits,
                    targetPeakNits * diffuseWhiteFraction);
                float diffuseWhiteOutput =
                    targetDiffuseWhiteNits / targetPeakNits;
                float sourceRelativeToDiffuseWhite =
                    sourceLumaNits / hdrDiffuseWhiteNits;
                float filmicLuma = diffuseWhiteOutput * pow(
                    max(sourceRelativeToDiffuseWhite, 0.0),
                    midtonePower);
                // A C1-continuous rational shoulder starts at diffuse white and
                // approaches the selected display peak without hard clipping.
                float kneeStart = diffuseWhiteOutput;
                float displayLuma = filmicLuma;
                if (filmicLuma > kneeStart) {
                    float shoulderRange = 1.0 - kneeStart;
                    float excess = filmicLuma - kneeStart;
                    float sourcePeakNits = clamp(
                        uHdrSourcePeakNits,
                        hdrDiffuseWhiteNits,
                        10000.0);
                    float peakFilmicLuma = diffuseWhiteOutput * pow(
                        sourcePeakNits / hdrDiffuseWhiteNits,
                        midtonePower);
                    float peakExcess = max(peakFilmicLuma - kneeStart, 0.0001);
                    // Preserve the pre-metadata 1000-nit curve exactly when
                    // metadata is unavailable, ignored, or manually set to the
                    // fallback. Other source peaks move only the shoulder.
                    const float fallbackSourcePeakNits = 1000.0;
                    float fallbackPeakFilmicLuma = diffuseWhiteOutput * pow(
                        fallbackSourcePeakNits / hdrDiffuseWhiteNits,
                        midtonePower);
                    float fallbackPeakExcess = max(
                        fallbackPeakFilmicLuma - kneeStart,
                        0.0001);
                    float fallbackPeakOutput = kneeStart +
                        shoulderRange * fallbackPeakExcess /
                        (fallbackPeakExcess + shoulderRange);
                    float fallRatio = uHdrMaxFallNits > 0.0
                        ? clamp(uHdrMaxFallNits / sourcePeakNits, 0.0, 1.0)
                        : 0.4;
                    // Map the validated source peak near, but never onto, the
                    // display ceiling. MaxFALL has only a deliberately small
                    // influence so questionable authoring metadata cannot
                    // radically alter the image.
                    float desiredPeakOutput = clamp(
                        fallbackPeakOutput + (0.4 - fallRatio) * 0.01,
                        kneeStart + shoulderRange * 0.50,
                        0.99);
                    float desiredShoulderFraction = clamp(
                        (desiredPeakOutput - kneeStart) / shoulderRange,
                        0.50,
                        0.98);
                    float shoulderSoftness = peakExcess *
                        (1.0 - desiredShoulderFraction) /
                        desiredShoulderFraction;
                    displayLuma = kneeStart +
                        shoulderRange * excess /
                        (excess + max(shoulderSoftness, 0.0001));
                }
                // Scale all channels together so highlight compression does not
                // introduce the hue shifts caused by per-channel tone mapping.
                return sourceNits *
                    (displayLuma / max(sourceLumaNits, 0.0001));
            }
            vec3 hlgToTargetDisplay(vec3 sceneLinear, vec3 lumaCoefficients) {
                sceneLinear = max(sceneLinear, vec3(0.0));
                float sceneLuma = max(dot(sceneLinear, lumaCoefficients), 0.0);
                float ootfScale = pow(
                    max(sceneLuma, 0.000001),
                    uHlgSystemGamma - 1.0);
                // The result is display-referred relative light: 1.0 is the
                // selected eye-level target peak of the headset.
                return sceneLinear * ootfScale;
            }
            void main() {
                vec2 uv = vUv;
                if (uRotationQuarterTurns == 1) {
                    uv = vec2(1.0 - uv.y, uv.x);
                } else if (uRotationQuarterTurns == 2) {
                    uv = vec2(1.0 - uv.x, 1.0 - uv.y);
                } else if (uRotationQuarterTurns == 3) {
                    uv = vec2(uv.y, 1.0 - uv.x);
                }
                if (uMode == 1 || uMode == 3) uv.x = uv.x * 0.5 + float(uEye) * 0.5;
                if (uMode == 2 || uMode == 4) uv.y = uv.y * 0.5 + (uEye == 0 ? 0.5 : 0.0);
                vec2 transformed = (uTexTransform * vec4(uv, 0.0, 1.0)).xy;
                vec4 sampleColor = texture(uVideo, transformed);
                vec3 rgb = sampleColor.rgb;
                int transfer = uTransfer;
                int primaries = uPrimaries;
                if (uPictureMode != 0) {
                    if (uPictureMode == 2) {
                        transfer = 0;
                        primaries = 0;
                    } else if (uPictureMode == 3) {
                        transfer = 1;
                        primaries = 1;
                    } else if (uPictureMode == 4) {
                        transfer = 2;
                        primaries = 1;
                    }
                    if (transfer == 1) {
                        vec3 sourceLuma =
                            primaries == 1 ? bt2020Luma : bt709Luma;
                        rgb = pqToTargetDisplay(pqToNits(rgb), sourceLuma);
                        if (primaries == 1) rgb = bt2020ToBt709(rgb);
                    } else if (transfer == 2) {
                        vec3 sourceLuma =
                            primaries == 1 ? bt2020Luma : bt709Luma;
                        rgb = hlgToTargetDisplay(
                            hlgToSceneLinear(rgb),
                            sourceLuma);
                        if (primaries == 1) rgb = bt2020ToBt709(rgb);
                    } else {
                        // SurfaceTexture has already converted decoder YUV and legal range to RGB.
                        rgb = bt709ToLinear(rgb);
                        if (primaries == 1) rgb = bt2020ToBt709(rgb);
                    }
                }
                rgb *= exp2(uExposure);
                float effectiveContrast = uContrast;
                if (
                    uPictureMode != 0 &&
                    (transfer == 1 || transfer == 2)
                ) {
                    // PQ and HLG are already shaped by their transfer and
                    // tone-mapping curves. Keep the user control granular by
                    // reducing only its distance from neutral by 20x.
                    effectiveContrast = 1.0 + (uContrast - 1.0) * 0.05;
                }
                rgb = (rgb - vec3(0.18)) * effectiveContrast + vec3(0.18);
                float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                rgb = mix(vec3(luma), rgb, uSaturation);
                rgb += vec3(uBrightness);
                bool convertedFromWideGamut = primaries == 1;
                rgb = mapOutputGamut(rgb, convertedFromWideGamut);
                rgb = perceptualDisplayFinish(rgb);
                rgb = mapOutputGamut(rgb, convertedFromWideGamut);
                fragColor = vec4(clamp(rgb, 0.0, 1.0), sampleColor.a);
            }
        )glsl";
        const char* subtitleFragment = R"glsl(#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uSubtitle;
            uniform vec3 uTextTint;
            uniform float uTextBrightness;
            uniform int uApplyTextStyle;
            out vec4 fragColor;
            void main() {
                vec4 sampleColor = texture(uSubtitle, vec2(vUv.x, 1.0 - vUv.y));
                if (uApplyTextStyle != 0) {
                    sampleColor.rgb *= uTextTint * uTextBrightness;
                }
                fragColor = sampleColor;
            }
        )glsl";
        const char* pointerFragment = R"glsl(#version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() { fragColor = uColor; }
        )glsl";
        const char* adaptiveAnalysisVertex = R"glsl(#version 300 es
            layout(location=0) in vec3 aPosition;
            layout(location=1) in vec2 aUv;
            out vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vUv = aUv;
            }
        )glsl";
        const char* adaptiveAnalysisFragment = R"glsl(#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            in vec2 vUv;
            uniform samplerExternalOES uVideo;
            uniform mat4 uTexTransform;
            uniform int uPrimaries;
            out vec4 fragColor;
            vec3 pqToNits(vec3 value) {
                const float m1 = 2610.0 / 16384.0;
                const float m2 = 2523.0 / 32.0;
                const float c1 = 3424.0 / 4096.0;
                const float c2 = 2413.0 / 128.0;
                const float c3 = 2392.0 / 128.0;
                vec3 p = pow(max(value, vec3(0.0)), vec3(1.0 / m2));
                vec3 numerator = max(p - vec3(c1), vec3(0.0));
                vec3 denominator = max(vec3(c2) - vec3(c3) * p, vec3(0.000001));
                return 10000.0 * pow(numerator / denominator, vec3(1.0 / m1));
            }
            void main() {
                vec2 transformed =
                    (uTexTransform * vec4(vUv, 0.0, 1.0)).xy;
                vec3 sourceNits = pqToNits(texture(uVideo, transformed).rgb);
                vec3 luma = uPrimaries == 1
                    ? vec3(0.2627, 0.6780, 0.0593)
                    : vec3(0.2126, 0.7152, 0.0722);
                float sourceLumaNits = max(dot(sourceNits, luma), 0.0);
                float encoded = clamp(
                    log2(1.0 + sourceLumaNits) / log2(10001.0),
                    0.0,
                    1.0);
                fragColor = vec4(encoded, encoded, encoded, 1.0);
            }
        )glsl";
        videoProgram_ = makeProgram(vertex, videoFragment);
        adaptiveAnalysisProgram_ =
            makeProgram(adaptiveAnalysisVertex, adaptiveAnalysisFragment);
        subtitleProgram_ = makeProgram(vertex, subtitleFragment);
        pointerProgram_ = makeProgram(vertex, pointerFragment);
        glGenFramebuffers(1, &framebuffer_);
        glGenFramebuffers(1, &adaptiveAnalysisFramebuffer_);
        glGenTextures(1, &adaptiveAnalysisTexture_);
        glBindTexture(GL_TEXTURE_2D, adaptiveAnalysisTexture_);
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA8,
            kAdaptiveAnalysisWidth,
            kAdaptiveAnalysisHeight,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindFramebuffer(GL_FRAMEBUFFER, adaptiveAnalysisFramebuffer_);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            adaptiveAnalysisTexture_,
            0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw std::runtime_error("Adaptive tone-map analysis framebuffer is incomplete");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glGenVertexArrays(1, &vertexArray_);
        glGenBuffers(1, &vertexBuffer_);
        glBindVertexArray(vertexArray_);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
        glBufferData(
            GL_ARRAY_BUFFER,
            sizeof(float) * kMaximumSurfaceVertices * 5,
            nullptr,
            GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                              reinterpret_cast<void*>(3 * sizeof(float)));

        glGenTextures(1, &videoTexture_);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexture_);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenTextures(1, &subtitleTexture_);
        glBindTexture(GL_TEXTURE_2D, subtitleTexture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        std::memcpy(textureTransform_, identity().m, sizeof(textureTransform_));
    }

    void loop() {
        while (!gStop.load()) {
            pollEvents();
            if (!sessionRunning_) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
            renderFrame();
        }
    }

    void pollEvents() {
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
            if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                const auto* changed = reinterpret_cast<XrEventDataSessionStateChanged*>(&event);
                sessionState_ = changed->state;
                if (sessionState_ == XR_SESSION_STATE_READY && !sessionRunning_) {
                    XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
                    begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    checkXr(xrBeginSession(session_, &begin), "xrBeginSession");
                    sessionRunning_ = true;
                } else if (sessionState_ == XR_SESSION_STATE_STOPPING && sessionRunning_) {
                    xrEndSession(session_);
                    sessionRunning_ = false;
                } else if (sessionState_ == XR_SESSION_STATE_EXITING ||
                           sessionState_ == XR_SESSION_STATE_LOSS_PENDING) {
                    gStop.store(true);
                }
            } else if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
                gStop.store(true);
            }
            event = {XR_TYPE_EVENT_DATA_BUFFER};
        }
    }

    void notifyPointer(float u, float v, int pointerState) {
        jclass cls = env_->GetObjectClass(activity_);
        jmethodID callback = env_->GetMethodID(cls, "onNativePointer", "(FFI)V");
        if (callback) env_->CallVoidMethod(activity_, callback, u, v, static_cast<jint>(pointerState));
        env_->DeleteLocalRef(cls);
    }

    void notifyScroll(float amount) {
        jclass cls = env_->GetObjectClass(activity_);
        jmethodID callback = env_->GetMethodID(cls, "onNativeScroll", "(F)V");
        if (callback) env_->CallVoidMethod(activity_, callback, amount);
        env_->DeleteLocalRef(cls);
    }

    void notifySeek(int direction, float u, float v) {
        if (seekCallback_) {
            env_->CallVoidMethod(
                activity_,
                seekCallback_,
                static_cast<jint>(direction),
                static_cast<jfloat>(u),
                static_cast<jfloat>(v));
        }
    }

    void notifyZoom(float amount, float u, float v) {
        if (zoomCallback_) {
            env_->CallVoidMethod(
                activity_,
                zoomCallback_,
                static_cast<jfloat>(amount),
                static_cast<jfloat>(u),
                static_cast<jfloat>(v));
        }
    }

    void notifyImmersiveOrientation(float yawDegrees, float pitchDegrees) {
        if (immersiveOrientationCallback_) {
            env_->CallVoidMethod(
                activity_,
                immersiveOrientationCallback_,
                static_cast<jfloat>(yawDegrees),
                static_cast<jfloat>(pitchDegrees));
        }
    }

    void resetHorizontalSeek() {
        horizontalSeekDirection_ = 0;
        horizontalSeekStarted_ = 0;
        lastHorizontalSeekCallback_ = 0;
    }

    bool updateHorizontalSeek(
        const XrVector2f& stick,
        float u,
        float v,
        XrTime displayTime) {
        const float horizontal = stick.x;
        const float vertical = stick.y;
        const bool deliberatelyHorizontal =
            std::abs(horizontal) >= 0.72f &&
            std::abs(horizontal) > std::abs(vertical) * 1.35f;
        const int direction = deliberatelyHorizontal ? (horizontal > 0.0f ? 1 : -1) : 0;

        if (direction == 0) {
            if (std::abs(horizontal) <= 0.35f) resetHorizontalSeek();
            return horizontalSeekDirection_ != 0;
        }

        if (direction != horizontalSeekDirection_) {
            horizontalSeekDirection_ = direction;
            horizontalSeekStarted_ = displayTime;
            lastHorizontalSeekCallback_ = displayTime;
            notifySeek(direction, u, v);
            return true;
        }

        constexpr XrDuration kInitialRepeatDelay = 650'000'000;
        constexpr XrDuration kRepeatInterval = 300'000'000;
        if (
            displayTime - horizontalSeekStarted_ >= kInitialRepeatDelay &&
            displayTime - lastHorizontalSeekCallback_ >= kRepeatInterval
        ) {
            lastHorizontalSeekCallback_ = displayTime;
            notifySeek(direction, u, v);
        }
        return true;
    }

    void notifyFrame(XrTime displayTime) {
        if (frameCallback_) {
            env_->CallVoidMethod(activity_, frameCallback_, static_cast<jlong>(displayTime));
        }
    }

    void updatePointer(XrTime displayTime) {
        XrActiveActionSet active{actionSet_, XR_NULL_PATH};
        XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
        sync.countActiveActionSets = 1;
        sync.activeActionSets = &active;
        if (XR_FAILED(xrSyncActions(session_, &sync))) {
            pointerVisible_ = false;
            return;
        }

        pointerVisible_ = false;
        if (gMenuInteractive.load()) {
            float scrollValue = 0.0f;
            for (int hand : {1, 0}) {
                XrActionStateGetInfo scrollGet{XR_TYPE_ACTION_STATE_GET_INFO};
                scrollGet.action = thumbstickAction_;
                scrollGet.subactionPath = handPaths_[hand];
                XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
                xrGetActionStateVector2f(session_, &scrollGet, &stick);
                if (stick.isActive &&
                    std::abs(stick.currentState.y) > std::abs(scrollValue)) {
                    scrollValue = stick.currentState.y;
                }
            }
            if (std::abs(scrollValue) < 0.12f) scrollValue = 0.0f;
            const bool changed = std::abs(scrollValue - lastScrollValue_) > 0.015f;
            const bool refresh = scrollValue != 0.0f &&
                (lastScrollCallback_ == 0 ||
                 displayTime - lastScrollCallback_ > 33'000'000);
            if (changed || refresh) {
                notifyScroll(scrollValue);
                lastScrollValue_ = scrollValue;
                lastScrollCallback_ = displayTime;
            }
        }
        for (int hand : {1, 0}) {
            XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};
            get.action = selectAction_;
            get.subactionPath = handPaths_[hand];
            XrActionStateBoolean select{XR_TYPE_ACTION_STATE_BOOLEAN};
            xrGetActionStateBoolean(session_, &get, &select);
            const bool wasDown = selectDown_[hand];
            const bool isDown = select.isActive && select.currentState;
            const bool pressed = isDown && !wasDown;
            const bool released = !isDown && wasDown;
            selectDown_[hand] = isDown;

            if (released && menuPointerHand_ == hand) {
                if (gMenuInteractive.load()) {
                    notifyPointer(lastMenuPointerU_, lastMenuPointerV_, 3);
                }
                menuPointerHand_ = -1;
                if (gMenuInteractive.load()) return;
            }

            if (!gMenuInteractive.load() && released && draggingHand_ == hand) {
                const bool wasImmersiveDrag = draggingImmersive_;
                const bool moved = dragMoved_;
                draggingHand_ = -1;
                draggingImmersive_ = false;
                if (wasImmersiveDrag && moved) {
                    notifyImmersiveOrientation(
                        gImmersiveYawDegrees.load(),
                        gImmersivePitchDegrees.load());
                } else if (!moved) {
                    notifyPointer(-1.0f, -1.0f, 1);
                }
                return;
            }

            get.action = aimAction_;
            XrActionStatePose poseState{XR_TYPE_ACTION_STATE_POSE};
            if (XR_FAILED(xrGetActionStatePose(session_, &get, &poseState)) || !poseState.isActive) continue;
            XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
            if (XR_FAILED(xrLocateSpace(aimSpaces_[hand], space_, displayTime, &location))) continue;
            const XrSpaceLocationFlags required =
                XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
            if ((location.locationFlags & required) != required) continue;

            const Vec3 origin{location.pose.position.x, location.pose.position.y, location.pose.position.z};
            const Vec3 direction = normalized(rotateVector(location.pose.orientation, {0, 0, -1}));

            if (!gMenuInteractive.load() && draggingHand_ == hand && isDown) {
                resetHorizontalSeek();
                if (draggingImmersive_) {
                    const float targetYaw =
                        std::atan2(direction.x, -direction.z);
                    const float yawDelta = normalizedAngleRadians(
                        targetYaw - immersiveDragStartPointerYaw_);
                    const float yaw = normalizedAngleRadians(
                        immersiveDragStartVideoYaw_ + yawDelta);
                    gImmersiveYawDegrees.store(yaw * 180.0f / kPi);
                    if (std::abs(yawDelta) > 1.0f * kPi / 180.0f) {
                        dragMoved_ = true;
                    }
                    pointerVisible_ = true;
                    pointerOrigin_ = origin;
                    pointerHit_ = origin + direction * 2.0f;
                    return;
                }
                Vec3 target{};
                if (!intersectRayWithSphere(origin, direction, dragRadius_, &target)) continue;
                const float targetYaw = std::atan2(target.x, -target.z);
                const float targetPitch = std::asin(
                    std::clamp(target.y / dragRadius_, -1.0f, 1.0f));
                const float yaw = targetYaw + dragYawOffset_;
                const float pitch = std::clamp(
                    targetPitch + dragPitchOffset_,
                    -70.0f * kPi / 180.0f,
                    70.0f * kPi / 180.0f);
                const float horizontalRadius = dragRadius_ * std::cos(pitch);
                const Vec3 newCenter{
                    horizontalRadius * std::sin(yaw),
                    dragRadius_ * std::sin(pitch),
                    -horizontalRadius * std::cos(yaw),
                };
                gScreenX.store(newCenter.x);
                gScreenY.store(newCenter.y);
                gScreenZ.store(newCenter.z);
                const Vec3 movement = newCenter - dragStartCenter_;
                if (dot(movement, movement) > 0.0025f) dragMoved_ = true;
                pointerVisible_ = true;
                pointerOrigin_ = origin;
                pointerHit_ = target;
                return;
            }

            const bool immersiveProjection = gVideoProjection.load() != 0;
            const bool immersivePlaybackUi =
                gImmersivePlaybackUi.load();
            const ScreenBasis basis =
                gMenuInteractive.load() && immersivePlaybackUi
                    ? anchoredImmersiveMenuBasis_
                    : currentScreenBasis();
            const float halfHeight = gScreenHalfHeight.load();
            const float videoHalfWidth =
                halfHeight * std::clamp(
                    gAspect.load(),
                    kMinimumVideoAspect,
                    kMaximumVideoAspect);
            Vec3 hit{};
            float u = 0.0f;
            float v = 0.0f;
            bool inside = false;
            if (gMenuInteractive.load()) {
                const float menuHalfWidth = halfHeight * kMenuAspect;
                const float menuReferenceHalfWidth =
                    overlayCurvatureReferenceHalfWidth(
                        videoHalfWidth, menuHalfWidth);
                inside = intersectRayWithCurvedScreen(
                    origin,
                    direction,
                    basis,
                    menuHalfWidth,
                    menuReferenceHalfWidth,
                    halfHeight,
                    gScreenCurvature.load(),
                    0.03f,
                    &hit,
                    &u,
                    &v);
                if (!inside) {
                    float videoU = 0.0f;
                    // Preserve picture clicks on portions of ultra-wide video that
                    // extend beyond the fixed-aspect menu texture.
                    inside = intersectRayWithCurvedScreen(
                        origin,
                        direction,
                        basis,
                        videoHalfWidth,
                        videoHalfWidth,
                        halfHeight,
                        gScreenCurvature.load(),
                        0.03f,
                        &hit,
                        &videoU,
                        &v);
                    if (inside) u = videoU < 0.5f ? -0.01f : 1.01f;
                }
            } else if (immersiveProjection) {
                inside = true;
                hit = origin + direction * 2.0f;
                u = 0.5f;
                v = 0.5f;
            } else {
                inside = intersectRayWithCurvedScreen(
                    origin,
                    direction,
                    basis,
                    videoHalfWidth,
                    videoHalfWidth,
                    halfHeight,
                    gScreenCurvature.load(),
                    0.03f,
                    &hit,
                    &u,
                    &v);
            }
            if (inside) {
                get.action = thumbstickAction_;
                XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
                xrGetActionStateVector2f(session_, &get, &stick);
                if (
                    stick.isActive &&
                    updateHorizontalSeek(stick.currentState, u, v, displayTime)
                ) {
                    pointerVisible_ = true;
                    pointerOrigin_ = origin;
                    pointerHit_ = hit;
                    return;
                }
                if (!stick.isActive) resetHorizontalSeek();
                if (
                    gMenuInteractive.load() &&
                    stick.isActive &&
                    std::abs(stick.currentState.y) > 0.18f &&
                    std::abs(stick.currentState.y) >
                        std::abs(stick.currentState.x) * 1.15f &&
                    (lastZoomCallback_ == 0 ||
                     displayTime - lastZoomCallback_ > 16'000'000)
                ) {
                    notifyZoom(stick.currentState.y, u, v);
                    lastZoomCallback_ = displayTime;
                }
            }
            if (gMenuInteractive.load()) {
                if (menuPointerHand_ == hand && isDown) {
                    lastMenuPointerU_ = u;
                    lastMenuPointerV_ = v;
                    pointerVisible_ = true;
                    pointerOrigin_ = origin;
                    pointerHit_ = hit;
                    if (lastPointerCallback_ == 0 ||
                        displayTime - lastPointerCallback_ > 16'000'000) {
                        notifyPointer(u, v, 2);
                        lastPointerCallback_ = displayTime;
                    }
                    return;
                }
                if (!inside) continue;
                pointerVisible_ = true;
                pointerOrigin_ = origin;
                pointerHit_ = hit;
                lastMenuPointerU_ = u;
                lastMenuPointerV_ = v;
                if (pressed) {
                    menuPointerHand_ = hand;
                    notifyPointer(u, v, 1);
                    lastPointerCallback_ = displayTime;
                } else if (lastPointerCallback_ == 0 ||
                           displayTime - lastPointerCallback_ > 16'000'000) {
                    notifyPointer(u, v, 0);
                    lastPointerCallback_ = displayTime;
                }
                return;
            }

            if (immersiveProjection) {
                if (pressed) {
                    draggingHand_ = hand;
                    draggingImmersive_ = true;
                    immersiveDragStartPointerYaw_ =
                        std::atan2(direction.x, -direction.z);
                    immersiveDragStartVideoYaw_ =
                        gImmersiveYawDegrees.load() * kPi / 180.0f;
                    dragMoved_ = false;
                }
                if (inside) {
                    pointerVisible_ = true;
                    pointerOrigin_ = origin;
                    pointerHit_ = hit;
                }
                return;
            }

            if (pressed && inside) {
                dragStartCenter_ = basis.center;
                dragRadius_ = std::clamp(length(basis.center), 0.5f, 8.0f);
                Vec3 target{};
                if (!intersectRayWithSphere(origin, direction, dragRadius_, &target)) continue;
                const float centerYaw = std::atan2(basis.center.x, -basis.center.z);
                const float centerPitch = std::asin(
                    std::clamp(basis.center.y / dragRadius_, -1.0f, 1.0f));
                const float targetYaw = std::atan2(target.x, -target.z);
                const float targetPitch = std::asin(
                    std::clamp(target.y / dragRadius_, -1.0f, 1.0f));
                draggingHand_ = hand;
                dragYawOffset_ = centerYaw - targetYaw;
                dragPitchOffset_ = centerPitch - targetPitch;
                dragMoved_ = false;
                pointerVisible_ = true;
                pointerOrigin_ = origin;
                pointerHit_ = hit;
                return;
            }

            if (inside) {
                get.action = thumbstickAction_;
                XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
                xrGetActionStateVector2f(session_, &get, &stick);
                if (stick.isActive && std::abs(stick.currentState.y) > 0.18f) {
                    const float oldSize = gScreenHalfHeight.load();
                    const float newSize = std::clamp(oldSize + stick.currentState.y * 0.012f, 0.45f, 1.55f);
                    gScreenHalfHeight.store(newSize);
                    pointerVisible_ = true;
                    pointerOrigin_ = origin;
                    pointerHit_ = hit;
                    return;
                }
            }
        }
        resetHorizontalSeek();
    }

    bool adaptiveToneMappingActive() const {
        if (!gAdaptiveToneMapEnabled.load() || !gHasVideo.load()) return false;
        const int pictureMode = gPictureMode.load();
        if (pictureMode == 0 || pictureMode == 2 || pictureMode == 4) return false;
        const int effectiveTransfer = pictureMode == 3 ? 1 : gVideoTransfer.load();
        return effectiveTransfer == 1;
    }

    static float smoothstepLogNits(float value, float lower, float upper) {
        const float logValue = std::log2(std::max(value, 0.0f) + 1.0f);
        const float logLower = std::log2(lower + 1.0f);
        const float logUpper = std::log2(upper + 1.0f);
        float normalized = std::clamp(
            (logValue - logLower) / std::max(logUpper - logLower, 0.0001f),
            0.0f,
            1.0f);
        return normalized * normalized * (3.0f - 2.0f * normalized);
    }

    void resetAdaptiveAnalysis(float staticTargetNits) {
        adaptiveHistoryValid_ = false;
        lastAnalyzedVideoTimestampNanos_ = 0;
        adaptiveEffectiveTargetNits_ = staticTargetNits;
        gAdaptiveToneMapEffectiveNits.store(staticTargetNits);
    }

    void analyzeAdaptiveToneMapping(int64_t videoTimestampNanos) {
        const float staticTargetNits = gToneMapPeakNits.load();
        if (!adaptiveToneMappingActive()) {
            if (adaptiveHistoryValid_ ||
                gAdaptiveToneMapEffectiveNits.load() != staticTargetNits) {
                resetAdaptiveAnalysis(staticTargetNits);
            }
            return;
        }

        const uint32_t resetGeneration = gAdaptiveToneMapResetGeneration.load();
        if (resetGeneration != adaptiveResetGeneration_) {
            adaptiveResetGeneration_ = resetGeneration;
            resetAdaptiveAnalysis(staticTargetNits);
        }
        if (videoTimestampNanos <= 0 ||
            videoTimestampNanos == lastAnalyzedVideoTimestampNanos_) {
            return;
        }

        constexpr std::array<float, 6 * 5> analysisVertices = {
            -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
             1.0f,  1.0f, 0.0f, 1.0f, 1.0f,
            -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 0.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 0.0f, 1.0f,
        };
        glBindFramebuffer(GL_FRAMEBUFFER, adaptiveAnalysisFramebuffer_);
        glViewport(0, 0, kAdaptiveAnalysisWidth, kAdaptiveAnalysisHeight);
        glDisable(GL_DEPTH_TEST);
        glUseProgram(adaptiveAnalysisProgram_);
        glBindVertexArray(vertexArray_);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
        glBufferSubData(
            GL_ARRAY_BUFFER,
            0,
            analysisVertices.size() * sizeof(float),
            analysisVertices.data());
        glUniformMatrix4fv(
            glGetUniformLocation(adaptiveAnalysisProgram_, "uTexTransform"),
            1,
            GL_FALSE,
            textureTransform_);
        const int pictureMode = gPictureMode.load();
        const int effectivePrimaries =
            pictureMode == 3 ? 1 : gVideoPrimaries.load();
        glUniform1i(
            glGetUniformLocation(adaptiveAnalysisProgram_, "uPrimaries"),
            effectivePrimaries);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexture_);
        glUniform1i(
            glGetUniformLocation(adaptiveAnalysisProgram_, "uVideo"),
            0);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glReadPixels(
            0,
            0,
            kAdaptiveAnalysisWidth,
            kAdaptiveAnalysisHeight,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            adaptiveReadback_.data());

        constexpr size_t sampleCount =
            kAdaptiveAnalysisWidth * kAdaptiveAnalysisHeight;
        std::array<float, sampleCount> sourceNits{};
        std::array<float, 16> histogram{};
        float meanNits = 0.0f;
        float meanEncoded = 0.0f;
        for (size_t index = 0; index < sampleCount; ++index) {
            const float encoded =
                static_cast<float>(adaptiveReadback_[index * 4]) / 255.0f;
            const float nits =
                std::exp2(encoded * std::log2(10001.0f)) - 1.0f;
            sourceNits[index] = nits;
            meanNits += nits;
            meanEncoded += encoded;
            const size_t bin = std::min(
                static_cast<size_t>(encoded * histogram.size()),
                histogram.size() - 1);
            histogram[bin] += 1.0f;
        }
        meanNits /= static_cast<float>(sampleCount);
        meanEncoded /= static_cast<float>(sampleCount);

        auto sortedNits = sourceNits;
        std::sort(sortedNits.begin(), sortedNits.end());
        const auto percentile = [&sortedNits](float fraction) {
            const size_t index = static_cast<size_t>(std::lround(
                fraction * static_cast<float>(sortedNits.size() - 1)));
            return sortedNits[std::min(index, sortedNits.size() - 1)];
        };
        const float p50Nits = percentile(0.50f);
        const float p90Nits = percentile(0.90f);
        const float p99Nits = percentile(0.99f);

        const float fieldScore = smoothstepLogNits(p90Nits, 40.0f, 250.0f);
        const float highlightScore =
            smoothstepLogNits(p99Nits, 250.0f, 1200.0f);
        const float averageScore = smoothstepLogNits(meanNits, 20.0f, 160.0f);
        const float baseBrightnessScore = std::clamp(
            fieldScore * 0.50f +
                highlightScore * 0.35f +
                averageScore * 0.15f,
            0.0f,
            1.0f);
        // Tone Priority should not globally behave like another exposure
        // control. It becomes influential only when a dark foreground and a
        // bright upper range coexist. At the 0.5 default the bias is exactly
        // zero, preserving the original adaptive algorithm bit-for-bit.
        const float contrastStops = std::log2(
            (p90Nits + 1.0f) / (p50Nits + 1.0f));
        float contrastStrength = std::clamp(
            (contrastStops - 2.5f) / (6.0f - 2.5f),
            0.0f,
            1.0f);
        contrastStrength =
            contrastStrength * contrastStrength * (3.0f - 2.0f * contrastStrength);
        const float darkForegroundStrength =
            1.0f - smoothstepLogNits(p50Nits, 20.0f, 100.0f);
        const float highContrastStrength =
            contrastStrength * (0.40f + 0.60f * darkForegroundStrength);
        const float priorityBias =
            (gAdaptiveToneMapPriority.load() - 0.5f) * 2.0f;
        const float brightnessScore = std::clamp(
            baseBrightnessScore +
                priorityBias * highContrastStrength * 0.30f,
            0.0f,
            1.0f);
        const float minimumTarget = gAdaptiveToneMapMinNits.load();
        const float maximumTarget = gAdaptiveToneMapMaxNits.load();
        const float desiredTarget =
            minimumTarget + (maximumTarget - minimumTarget) * brightnessScore;

        bool cutDetected = false;
        float elapsedSeconds = 0.0f;
        if (adaptiveHistoryValid_) {
            elapsedSeconds = static_cast<float>(
                videoTimestampNanos - lastAnalyzedVideoTimestampNanos_) /
                1'000'000'000.0f;
            float structuralDifference = 0.0f;
            std::array<float, 16> previousHistogram{};
            float previousMean = 0.0f;
            for (float previous : previousAdaptiveLogLuma_) {
                previousMean += previous;
                const size_t bin = std::min(
                    static_cast<size_t>(previous * previousHistogram.size()),
                    previousHistogram.size() - 1);
                previousHistogram[bin] += 1.0f;
            }
            previousMean /= static_cast<float>(sampleCount);
            for (size_t index = 0; index < sampleCount; ++index) {
                const float current =
                    static_cast<float>(adaptiveReadback_[index * 4]) / 255.0f;
                const float previous = previousAdaptiveLogLuma_[index];
                structuralDifference += std::abs(
                    (current - meanEncoded) - (previous - previousMean));
            }
            structuralDifference /= static_cast<float>(sampleCount);
            float histogramDistance = 0.0f;
            for (size_t bin = 0; bin < histogram.size(); ++bin) {
                histogramDistance +=
                    std::abs(histogram[bin] - previousHistogram[bin]);
            }
            histogramDistance /= 2.0f * static_cast<float>(sampleCount);
            const bool timestampDiscontinuity =
                elapsedSeconds <= 0.0f || elapsedSeconds > 0.50f;
            cutDetected =
                timestampDiscontinuity ||
                (histogramDistance > 0.28f &&
                    structuralDifference > 0.045f) ||
                structuralDifference > 0.13f;
        }

        if (!adaptiveHistoryValid_ || cutDetected) {
            adaptiveEffectiveTargetNits_ = desiredTarget;
            if (cutDetected) {
                LOGI(
                    "Adaptive HDR cut: target %.0f nits (P90 %.0f, P99 %.0f, mean %.0f)",
                    desiredTarget,
                    p90Nits,
                    p99Nits,
                    meanNits);
            }
        } else {
            const float difference =
                desiredTarget - adaptiveEffectiveTargetNits_;
            if (std::abs(difference) > 4.0f) {
                const bool smoothMode = gAdaptiveToneMapMode.load() == 1;
                const float timeConstant = difference > 0.0f
                    ? (smoothMode ? 4.0f : 2.0f)
                    : (smoothMode ? 8.0f : 5.0f);
                const float alpha = 1.0f - std::exp(
                    -std::clamp(elapsedSeconds, 0.0f, 0.25f) /
                    timeConstant);
                adaptiveEffectiveTargetNits_ += difference * alpha;
            }
        }
        adaptiveEffectiveTargetNits_ = std::clamp(
            adaptiveEffectiveTargetNits_,
            minimumTarget,
            maximumTarget);
        gAdaptiveToneMapEffectiveNits.store(adaptiveEffectiveTargetNits_);
        for (size_t index = 0; index < sampleCount; ++index) {
            previousAdaptiveLogLuma_[index] =
                static_cast<float>(adaptiveReadback_[index * 4]) / 255.0f;
        }
        adaptiveHistoryValid_ = true;
        lastAnalyzedVideoTimestampNanos_ = videoTimestampNanos;
    }

    void updateHeadRelativeState(uint32_t viewCount) {
        if (viewCount == 0) return;
        Vec3 position{};
        for (uint32_t index = 0; index < viewCount; ++index) {
            position = position + Vec3{
                views_[index].pose.position.x,
                views_[index].pose.position.y,
                views_[index].pose.position.z,
            };
        }
        headCenter_ = position * (1.0f / static_cast<float>(viewCount));
        const XrQuaternionf orientation = views_[0].pose.orientation;
        const Vec3 forward = normalized(
            rotateVector(orientation, {0.0f, 0.0f, -1.0f}));
        // Keep the panel facing the user's gaze, but derive its axes from world-up.
        // Head pitch is preserved while head roll can never leave a recalled
        // playback bar crooked.
        const Vec3 right = normalized(
            cross(forward, {0.0f, 1.0f, 0.0f}),
            {1.0f, 0.0f, 0.0f});
        const Vec3 up = normalized(
            cross(right, forward),
            {0.0f, 1.0f, 0.0f});
        headRelativeMenuBasis_ = {
            headCenter_ + forward * 2.0f,
            right,
            up,
            forward * -1.0f,
        };
        const uint64_t requestedAnchorGeneration =
            gImmersiveMenuAnchorGeneration.load();
        if (
            requestedAnchorGeneration !=
            immersiveMenuAnchorGeneration_
        ) {
            anchoredImmersiveMenuBasis_ = headRelativeMenuBasis_;
            immersiveMenuAnchorGeneration_ = requestedAnchorGeneration;
        }
        gLatestHeadYawDegrees.store(
            std::atan2(forward.x, -forward.z) * 180.0f / kPi);
        gLatestHeadPitchDegrees.store(
            std::asin(std::clamp(forward.y, -1.0f, 1.0f)) *
            180.0f / kPi);
    }

    void renderFrame() {
        XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO};
        XrFrameState state{XR_TYPE_FRAME_STATE};
        checkXr(xrWaitFrame(session_, &wait, &state), "xrWaitFrame");
        XrFrameBeginInfo begin{XR_TYPE_FRAME_BEGIN_INFO};
        checkXr(xrBeginFrame(session_, &begin), "xrBeginFrame");

        std::vector<XrCompositionLayerProjectionView> projectionViews;
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        XrCompositionLayerPassthroughFB passthroughComposition{
            XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
        const XrCompositionLayerBaseHeader* layers[2]{};
        uint32_t layerCount = 0;
        const bool passthroughActive = updatePassthroughState();
        const bool explicitPassthrough =
            passthroughActive && fbPassthroughUsable_ && passthroughLayer_;
        if (state.shouldRender && gResumed.load()) {
            XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO};
            locate.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            locate.displayTime = state.predictedDisplayTime;
            locate.space = space_;
            XrViewState viewState{XR_TYPE_VIEW_STATE};
            uint32_t count = 0;
            checkXr(xrLocateViews(session_, &locate, &viewState,
                static_cast<uint32_t>(views_.size()), &count, views_.data()), "xrLocateViews");
            updateHeadRelativeState(count);
            updatePointer(state.predictedDisplayTime);
            if (gMenuInteractive.load()) notifyFrame(state.predictedDisplayTime);
            int64_t videoTimestampNanos = 0;
            videoSurface_.update(env_, textureTransform_, &videoTimestampNanos);
            analyzeAdaptiveToneMapping(videoTimestampNanos);
            uploadSubtitleIfNeeded();
            projectionViews.resize(count, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW});
            for (uint32_t eye = 0; eye < count; ++eye) {
                renderEye(eye, views_[eye], passthroughActive);
                auto& view = projectionViews[eye];
                view.pose = views_[eye].pose;
                view.fov = views_[eye].fov;
                view.subImage.swapchain = swapchains_[eye].handle;
                view.subImage.imageRect.extent = {swapchains_[eye].width, swapchains_[eye].height};
                view.subImage.imageArrayIndex = 0;
            }
            layer.space = space_;
            if (passthroughActive) {
                layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
            }
            layer.viewCount = count;
            layer.views = projectionViews.data();
            if (explicitPassthrough) {
                passthroughComposition.layerHandle = passthroughLayer_;
                layers[layerCount++] = reinterpret_cast<XrCompositionLayerBaseHeader*>(
                    &passthroughComposition);
            }
            layers[layerCount++] = reinterpret_cast<XrCompositionLayerBaseHeader*>(&layer);
        }

        XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};
        end.displayTime = state.predictedDisplayTime;
        end.environmentBlendMode =
            passthroughActive && !explicitPassthrough
                ? XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND
                : XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        end.layerCount = layerCount;
        end.layers = layerCount > 0 ? layers : nullptr;
        checkXr(xrEndFrame(session_, &end), "xrEndFrame");
    }

    void renderEye(uint32_t eye, const XrView& view, bool passthroughActive) {
        auto& chain = swapchains_[eye];
        uint32_t imageIndex = 0;
        XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        checkXr(xrAcquireSwapchainImage(chain.handle, &acquire, &imageIndex), "acquire image");
        XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wait.timeout = XR_INFINITE_DURATION;
        checkXr(xrWaitSwapchainImage(chain.handle, &wait), "wait image");

        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               chain.images[imageIndex].image, 0);
        glViewport(0, 0, chain.width, chain.height);
        if (passthroughActive) {
            const float dimmingAlpha = 1.0f - std::clamp(
                gPassthroughBrightness.load(), 0.0f, 1.0f);
            glClearColor(0.0f, 0.0f, 0.0f, dimmingAlpha);
        } else {
            const float sceneBrightness = gSceneBrightness.load();
            glClearColor(
                0.018f * sceneBrightness,
                0.024f * sceneBrightness,
                0.035f * sceneBrightness,
                1.0f);
        }
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_DEPTH_TEST);

        const Mat4 mvp = multiply(projection(view.fov), viewFromPose(view.pose));
        const float aspect = std::clamp(
            gAspect.load(),
            kMinimumVideoAspect,
            kMaximumVideoAspect);
        const float halfHeight = gScreenHalfHeight.load();
        const float halfWidth = halfHeight * aspect;
        const ScreenBasis basis =
            gMenuInteractive.load() && gImmersivePlaybackUi.load()
                ? anchoredImmersiveMenuBasis_
                : currentScreenBasis();
        const float curvature = gScreenCurvature.load();
        std::vector<float> videoVertices;
        videoVertices.reserve(kMaximumSurfaceVertices * 5);
        const auto appendVertex = [&videoVertices](
                                      const Vec3& point, float u, float v) {
            videoVertices.push_back(point.x);
            videoVertices.push_back(point.y);
            videoVertices.push_back(point.z);
            videoVertices.push_back(u);
            videoVertices.push_back(v);
        };
        const int projectionMode = gVideoProjection.load();
        if (projectionMode == 0) {
            for (int segment = 0; segment < kScreenSegments; ++segment) {
                const float u0 =
                    static_cast<float>(segment) /
                    static_cast<float>(kScreenSegments);
                const float u1 =
                    static_cast<float>(segment + 1) /
                    static_cast<float>(kScreenSegments);
                const float normalized0 = u0 * 2.0f - 1.0f;
                const float normalized1 = u1 * 2.0f - 1.0f;
                const Vec3 topLeft = curvedScreenPoint(
                    basis, halfWidth, normalized0, halfHeight, curvature);
                const Vec3 bottomLeft = curvedScreenPoint(
                    basis, halfWidth, normalized0, -halfHeight, curvature);
                const Vec3 bottomRight = curvedScreenPoint(
                    basis, halfWidth, normalized1, -halfHeight, curvature);
                const Vec3 topRight = curvedScreenPoint(
                    basis, halfWidth, normalized1, halfHeight, curvature);
                appendVertex(topLeft, u0, 1.0f);
                appendVertex(bottomLeft, u0, 0.0f);
                appendVertex(bottomRight, u1, 0.0f);
                appendVertex(topLeft, u0, 1.0f);
                appendVertex(bottomRight, u1, 0.0f);
                appendVertex(topRight, u1, 1.0f);
            }
        } else {
            const float horizontalFov =
                projectionMode == 1 ? kPi : 2.0f * kPi;
            const float yawOffset =
                gImmersiveYawDegrees.load() * kPi / 180.0f;
            const float pitchOffset =
                gImmersivePitchDegrees.load() * kPi / 180.0f;
            constexpr float radius = 10.0f;
            const auto immersivePoint =
                [&](float u, float v) {
                    const float yaw =
                        (u - 0.5f) * horizontalFov + yawOffset;
                    const float pitch = std::clamp(
                        (0.5f - v) * kPi + pitchOffset,
                        -0.5f * kPi,
                        0.5f * kPi);
                    const float horizontalRadius = std::cos(pitch);
                    const Vec3 direction{
                        std::sin(yaw) * horizontalRadius,
                        std::sin(pitch),
                        -std::cos(yaw) * horizontalRadius,
                    };
                    return headCenter_ + direction * radius;
                };
            std::vector<Vec3> immersivePoints(
                (kImmersiveHorizontalSegments + 1) *
                (kImmersiveVerticalSegments + 1));
            const auto immersivePointIndex = [](int column, int row) {
                return
                    row * (kImmersiveHorizontalSegments + 1) +
                    column;
            };
            for (int row = 0; row <= kImmersiveVerticalSegments; ++row) {
                const float v =
                    static_cast<float>(row) /
                    static_cast<float>(kImmersiveVerticalSegments);
                for (
                    int column = 0;
                    column <= kImmersiveHorizontalSegments;
                    ++column
                ) {
                    const float u =
                        static_cast<float>(column) /
                        static_cast<float>(kImmersiveHorizontalSegments);
                    immersivePoints[immersivePointIndex(column, row)] =
                        immersivePoint(u, v);
                }
            }
            for (
                int row = 0;
                row < kImmersiveVerticalSegments;
                ++row
            ) {
                const float v0 =
                    static_cast<float>(row) /
                    static_cast<float>(kImmersiveVerticalSegments);
                const float v1 =
                    static_cast<float>(row + 1) /
                    static_cast<float>(kImmersiveVerticalSegments);
                for (
                    int column = 0;
                    column < kImmersiveHorizontalSegments;
                    ++column
                ) {
                    const float u0 =
                        static_cast<float>(column) /
                        static_cast<float>(kImmersiveHorizontalSegments);
                    const float u1 =
                        static_cast<float>(column + 1) /
                        static_cast<float>(kImmersiveHorizontalSegments);
                    const Vec3 topLeft =
                        immersivePoints[immersivePointIndex(column, row)];
                    const Vec3 bottomLeft =
                        immersivePoints[immersivePointIndex(column, row + 1)];
                    const Vec3 bottomRight =
                        immersivePoints[
                            immersivePointIndex(column + 1, row + 1)];
                    const Vec3 topRight =
                        immersivePoints[
                            immersivePointIndex(column + 1, row)];
                    appendVertex(topLeft, u0, 1.0f - v0);
                    appendVertex(bottomLeft, u0, 1.0f - v1);
                    appendVertex(bottomRight, u1, 1.0f - v1);
                    appendVertex(topLeft, u0, 1.0f - v0);
                    appendVertex(bottomRight, u1, 1.0f - v1);
                    appendVertex(topRight, u1, 1.0f - v0);
                }
            }
        }
        glBindVertexArray(vertexArray_);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
        glBufferSubData(
            GL_ARRAY_BUFFER,
            0,
            videoVertices.size() * sizeof(float),
            videoVertices.data());
        const GLsizei videoVertexCount =
            static_cast<GLsizei>(videoVertices.size() / 5);
        if (gHasVideo.load()) {
            glUseProgram(videoProgram_);
            glUniformMatrix4fv(glGetUniformLocation(videoProgram_, "uMvp"), 1, GL_FALSE, mvp.m);
            glUniformMatrix4fv(glGetUniformLocation(videoProgram_, "uTexTransform"), 1, GL_FALSE,
                               textureTransform_);
            glUniform1i(glGetUniformLocation(videoProgram_, "uMode"), gStereoMode.load());
            const GLint sourceEye = static_cast<GLint>(gSwapEyes.load() ? 1u - eye : eye);
            glUniform1i(glGetUniformLocation(videoProgram_, "uEye"), sourceEye);
            glUniform1i(
                glGetUniformLocation(videoProgram_, "uRotationQuarterTurns"),
                gVideoProjection.load() == 0
                    ? gVideoRotationQuarterTurns.load()
                    : 0);
            glUniform1i(glGetUniformLocation(videoProgram_, "uPictureMode"), gPictureMode.load());
            glUniform1i(glGetUniformLocation(videoProgram_, "uTransfer"), gVideoTransfer.load());
            glUniform1i(glGetUniformLocation(videoProgram_, "uPrimaries"), gVideoPrimaries.load());
            glUniform1f(glGetUniformLocation(videoProgram_, "uBrightness"), gPictureBrightness.load());
            glUniform1f(glGetUniformLocation(videoProgram_, "uExposure"), gPictureExposure.load());
            glUniform1f(glGetUniformLocation(videoProgram_, "uContrast"), gPictureContrast.load());
            glUniform1f(glGetUniformLocation(videoProgram_, "uSaturation"), gPictureSaturation.load());
            glUniform1f(glGetUniformLocation(videoProgram_, "uVibrance"), gPictureVibrance.load());
            glUniform1f(
                glGetUniformLocation(videoProgram_, "uToneMapPeakNits"),
                adaptiveToneMappingActive()
                    ? gAdaptiveToneMapEffectiveNits.load()
                    : gToneMapPeakNits.load());
            glUniform1f(
                glGetUniformLocation(videoProgram_, "uHlgSystemGamma"),
                gHlgSystemGamma.load());
            glUniform1f(
                glGetUniformLocation(videoProgram_, "uHdrSourcePeakNits"),
                gHdrSourcePeakNits.load());
            glUniform1f(
                glGetUniformLocation(videoProgram_, "uHdrMaxFallNits"),
                gHdrMaxFallNits.load());
            glUniform1i(
                glGetUniformLocation(videoProgram_, "uGamutMappingMode"),
                gGamutMappingMode.load());
            glUniform1f(
                glGetUniformLocation(videoProgram_, "uCinemaToneDepth"),
                gCinemaToneDepth.load());
            glUniform1f(
                glGetUniformLocation(videoProgram_, "uCinemaMidtoneEv"),
                gCinemaMidtoneEv.load());
            glUniform1f(
                glGetUniformLocation(videoProgram_, "uCinemaColorIntensity"),
                gCinemaColorIntensity.load());
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTexture_);
            glUniform1i(glGetUniformLocation(videoProgram_, "uVideo"), 0);
            glDrawArrays(GL_TRIANGLES, 0, videoVertexCount);
        }

        if (gSubtitleWidth.load() > 0 && gSubtitleHeight.load() > 0) {
            const bool isMenuOverlay = gMenuInteractive.load();
            const bool isBitmapSubtitle =
                !isMenuOverlay && gSubtitleBitmapMode.load();
            const ScreenBasis subtitleBasis =
                projectionMode == 0
                    ? basis
                    : isMenuOverlay && gImmersivePlaybackUi.load()
                        ? anchoredImmersiveMenuBasis_
                        : isMenuOverlay
                            ? basis
                            : headRelativeMenuBasis_;
            float displayLeft = gSubtitleLeft.load();
            float displayTop = gSubtitleTop.load();
            float displayRight = gSubtitleRight.load();
            float displayBottom = gSubtitleBottom.load();
            float textureLeft = 0.0f;
            float textureTop = 0.0f;
            float textureRight = 1.0f;
            float textureBottom = 1.0f;
            bool hasStereoSubtitleRegion = true;
            const int stereoMode = gStereoMode.load();
            const bool horizontalStereo =
                stereoMode == 1 || stereoMode == 3;
            const bool verticalStereo =
                stereoMode == 2 || stereoMode == 4;
            const bool splitStereoSubtitle =
                isBitmapSubtitle &&
                gSubtitle3dEnabled.load() &&
                (horizontalStereo || verticalStereo);
            if (splitStereoSubtitle) {
                // Baked stereo subtitle disparity is often authored for a
                // different physical screen size and can be uncomfortable in
                // VR. Extract one correctly shaped view and present it to both
                // eyes; the existing 3D subtitle quad and depth control then
                // provide predictable binocular positioning. Swap Eyes picks
                // the alternate baked view when requested.
                const uint32_t sourceEye =
                    gSwapEyes.load() ? 1u : 0u;
                const float sourceStart =
                    sourceEye == 0u ? 0.0f : 0.5f;
                const float sourceEnd = sourceStart + 0.5f;
                if (horizontalStereo) {
                    const float originalLeft = displayLeft;
                    const float originalRight = displayRight;
                    const float selectedLeft =
                        std::max(originalLeft, sourceStart);
                    const float selectedRight =
                        std::min(originalRight, sourceEnd);
                    hasStereoSubtitleRegion =
                        selectedRight > selectedLeft &&
                        originalRight > originalLeft;
                    if (hasStereoSubtitleRegion) {
                        textureLeft =
                            (selectedLeft - originalLeft) /
                            (originalRight - originalLeft);
                        textureRight =
                            (selectedRight - originalLeft) /
                            (originalRight - originalLeft);
                        displayLeft =
                            (selectedLeft - sourceStart) * 2.0f;
                        displayRight =
                            (selectedRight - sourceStart) * 2.0f;
                        // Remapping source-half coordinates preserves the
                        // baked left/right disparity, but it also doubles the
                        // cropped bitmap's width. Restore the native aspect
                        // around that remapped center.
                        const float center =
                            (displayLeft + displayRight) * 0.5f;
                        const float halfDisplayWidth =
                            (displayRight - displayLeft) * 0.25f;
                        displayLeft = center - halfDisplayWidth;
                        displayRight = center + halfDisplayWidth;
                    }
                } else {
                    const float originalTop = displayTop;
                    const float originalBottom = displayBottom;
                    const float selectedTop =
                        std::max(originalTop, sourceStart);
                    const float selectedBottom =
                        std::min(originalBottom, sourceEnd);
                    hasStereoSubtitleRegion =
                        selectedBottom > selectedTop &&
                        originalBottom > originalTop;
                    if (hasStereoSubtitleRegion) {
                        textureTop =
                            (selectedTop - originalTop) /
                            (originalBottom - originalTop);
                        textureBottom =
                            (selectedBottom - originalTop) /
                            (originalBottom - originalTop);
                        displayTop =
                            (selectedTop - sourceStart) * 2.0f;
                        displayBottom =
                            (selectedBottom - sourceStart) * 2.0f;
                        const float center =
                            (displayTop + displayBottom) * 0.5f;
                        const float halfDisplayHeight =
                            (displayBottom - displayTop) * 0.25f;
                        displayTop = center - halfDisplayHeight;
                        displayBottom = center + halfDisplayHeight;
                    }
                }
            }
            float subtitleTop = 0.0f;
            float subtitleBottom = 0.0f;
            if (isBitmapSubtitle) {
                subtitleTop = halfHeight * (1.0f - 2.0f * displayTop);
                subtitleBottom = halfHeight * (1.0f - 2.0f * displayBottom);
            } else if (isMenuOverlay) {
                subtitleTop = halfHeight;
                subtitleBottom = -halfHeight;
            } else {
                const float textScale =
                    std::clamp(gTextSubtitleScale.load(), 0.6f, 1.8f);
                const float textHalfHeight = 0.15f * textScale;
                const float minimumCenter = -halfHeight + textHalfHeight + 0.01f;
                const float maximumCenter = halfHeight - textHalfHeight - 0.01f;
                const float verticalPosition = std::clamp(
                    gTextSubtitleVerticalPosition.load(), 0.0f, 1.0f);
                const float center = minimumCenter +
                    (maximumCenter - minimumCenter) * verticalPosition;
                subtitleTop = center + textHalfHeight;
                subtitleBottom = center - textHalfHeight;
            }
            const float subtitleTextureAspect =
                static_cast<float>(gSubtitleWidth.load()) /
                static_cast<float>(gSubtitleHeight.load());
            const float rawSubtitleHalfWidth =
                isMenuOverlay
                    ? halfHeight * kMenuAspect
                    : (subtitleTop - subtitleBottom) * 0.5f *
                        subtitleTextureAspect;
            const float subtitleHalfWidth =
                !isBitmapSubtitle && !isMenuOverlay
                    ? std::min(rawSubtitleHalfWidth, halfWidth * 0.98f)
                    : rawSubtitleHalfWidth;
            const float textHorizontalOffset =
                !isBitmapSubtitle && !isMenuOverlay
                    ? (gTextSubtitleHorizontalPosition.load() * 2.0f - 1.0f) *
                        std::max(0.0f, halfWidth - subtitleHalfWidth)
                    : 0.0f;
            const float subtitleForward =
                isMenuOverlay ? 0.01f : gSubtitleDepthMeters.load();
            const float subtitleReferenceHalfWidth =
                overlayCurvatureReferenceHalfWidth(
                    halfWidth, subtitleHalfWidth);
            std::array<float, kScreenSegments * 6 * 5> subtitleVertices{};
            size_t subtitleVertexCursor = 0;
            const auto appendSubtitleVertex =
                [&subtitleVertices, &subtitleVertexCursor](
                    const Vec3& point, float u, float v) {
                    subtitleVertices[subtitleVertexCursor++] = point.x;
                    subtitleVertices[subtitleVertexCursor++] = point.y;
                    subtitleVertices[subtitleVertexCursor++] = point.z;
                    subtitleVertices[subtitleVertexCursor++] = u;
                    subtitleVertices[subtitleVertexCursor++] = v;
                };
            for (int segment = 0; segment < kScreenSegments; ++segment) {
                const float u0 =
                    static_cast<float>(segment) /
                    static_cast<float>(kScreenSegments);
                const float u1 =
                    static_cast<float>(segment + 1) /
                    static_cast<float>(kScreenSegments);
                const float normalized0 =
                    isBitmapSubtitle
                        ? ((displayLeft +
                            (displayRight - displayLeft) * u0) *
                            2.0f - 1.0f)
                        : u0 * 2.0f - 1.0f;
                const float normalized1 =
                    isBitmapSubtitle
                        ? ((displayLeft +
                            (displayRight - displayLeft) * u1) *
                            2.0f - 1.0f)
                        : u1 * 2.0f - 1.0f;
                const auto subtitlePoint =
                    [&](float normalizedHorizontal, float vertical) {
                        return isBitmapSubtitle
                            ? curvedScreenPoint(
                                subtitleBasis,
                                halfWidth,
                                normalizedHorizontal,
                                vertical,
                                curvature,
                                subtitleForward)
                            : sharedCurvedScreenPoint(
                                subtitleBasis,
                                subtitleReferenceHalfWidth,
                                subtitleHalfWidth,
                                normalizedHorizontal +
                                    textHorizontalOffset /
                                        std::max(subtitleHalfWidth, 0.0001f),
                                vertical,
                                curvature,
                                subtitleForward);
                    };
                const Vec3 topLeft = subtitlePoint(normalized0, subtitleTop);
                const Vec3 bottomLeft = subtitlePoint(normalized0, subtitleBottom);
                const Vec3 bottomRight = subtitlePoint(normalized1, subtitleBottom);
                const Vec3 topRight = subtitlePoint(normalized1, subtitleTop);
                const float textureU0 =
                    textureLeft + (textureRight - textureLeft) * u0;
                const float textureU1 =
                    textureLeft + (textureRight - textureLeft) * u1;
                const float textureTopV = 1.0f - textureTop;
                const float textureBottomV = 1.0f - textureBottom;
                appendSubtitleVertex(topLeft, textureU0, textureTopV);
                appendSubtitleVertex(bottomLeft, textureU0, textureBottomV);
                appendSubtitleVertex(bottomRight, textureU1, textureBottomV);
                appendSubtitleVertex(topLeft, textureU0, textureTopV);
                appendSubtitleVertex(bottomRight, textureU1, textureBottomV);
                appendSubtitleVertex(topRight, textureU1, textureTopV);
            }
            if (hasStereoSubtitleRegion) {
                glBufferSubData(
                    GL_ARRAY_BUFFER,
                    0,
                    subtitleVertices.size() * sizeof(float),
                    subtitleVertices.data());
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glUseProgram(subtitleProgram_);
                glUniformMatrix4fv(
                    glGetUniformLocation(subtitleProgram_, "uMvp"),
                    1,
                    GL_FALSE,
                    mvp.m);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, subtitleTexture_);
                glUniform1i(
                    glGetUniformLocation(subtitleProgram_, "uSubtitle"),
                    0);
                const bool applyTextStyle = !isMenuOverlay && !isBitmapSubtitle;
                glUniform3f(
                    glGetUniformLocation(subtitleProgram_, "uTextTint"),
                    gTextSubtitleRed.load(),
                    gTextSubtitleGreen.load(),
                    gTextSubtitleBlue.load());
                glUniform1f(
                    glGetUniformLocation(subtitleProgram_, "uTextBrightness"),
                    gTextSubtitleBrightness.load());
                glUniform1i(
                    glGetUniformLocation(subtitleProgram_, "uApplyTextStyle"),
                    applyTextStyle ? 1 : 0);
                glDrawArrays(GL_TRIANGLES, 0, kScreenSegments * 6);
                glDisable(GL_BLEND);
            }
        }

        if (pointerVisible_) drawPointer(mvp);

        // Crop in angular space using each eye's actual asymmetric projection.
        // Opaque black also masks passthrough. No video rescale or extra render pass.
        if (gMetaGlassesFovEnabled.load()) {
            const float horizontal = std::tan(35.0f * kPi / 180.0f);
            const float vertical = std::tan(33.0f * kPi / 180.0f);
            const float left = std::tan(view.fov.angleLeft);
            const float right = std::tan(view.fov.angleRight);
            const float bottom = std::tan(view.fov.angleDown);
            const float top = std::tan(view.fov.angleUp);
            const auto edge = [](float tangent, float minimum, float maximum, int size) {
                return static_cast<int>(std::lround(std::clamp(
                    (tangent - minimum) / (maximum - minimum), 0.0f, 1.0f) * size));
            };
            const int x0 = edge(-horizontal, left, right, chain.width);
            const int x1 = edge(horizontal, left, right, chain.width);
            const int y0 = edge(-vertical, bottom, top, chain.height);
            const int y1 = edge(vertical, bottom, top, chain.height);
            glEnable(GL_SCISSOR_TEST);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            const auto black = [](int x, int y, int width, int height) {
                if (width <= 0 || height <= 0) return;
                glScissor(x, y, width, height);
                glClear(GL_COLOR_BUFFER_BIT);
            };
            black(0, 0, x0, chain.height);
            black(x1, 0, chain.width - x1, chain.height);
            black(x0, 0, x1 - x0, y0);
            black(x0, y1, x1 - x0, chain.height - y1);
            glDisable(GL_SCISSOR_TEST);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glFlush();
        XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        checkXr(xrReleaseSwapchainImage(chain.handle, &release), "release image");
    }

    void drawPointer(const Mat4& mvp) {
        glUseProgram(pointerProgram_);
        glUniformMatrix4fv(glGetUniformLocation(pointerProgram_, "uMvp"), 1, GL_FALSE, mvp.m);
        glUniform4f(glGetUniformLocation(pointerProgram_, "uColor"), 0.10f, 0.78f, 1.0f, 1.0f);
        const ScreenBasis basis =
            gMenuInteractive.load() && gImmersivePlaybackUi.load()
                ? anchoredImmersiveMenuBasis_
                : currentScreenBasis();
        const Vec3 lineEnd = pointerHit_ + basis.normal * 0.01f;
        const float line[] = {
            pointerOrigin_.x, pointerOrigin_.y, pointerOrigin_.z, 0, 0,
            lineEnd.x, lineEnd.y, lineEnd.z, 0, 0,
        };
        glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(line), line);
        glLineWidth(3.0f);
        glDrawArrays(GL_LINES, 0, 2);

        const float radius = 0.025f;
        const Vec3 dotCenter = pointerHit_ + basis.normal * 0.015f;
        const Vec3 dotTopLeft = dotCenter - basis.right * radius + basis.up * radius;
        const Vec3 dotBottomLeft = dotCenter - basis.right * radius - basis.up * radius;
        const Vec3 dotBottomRight = dotCenter + basis.right * radius - basis.up * radius;
        const Vec3 dotTopRight = dotCenter + basis.right * radius + basis.up * radius;
        const float dot[] = {
            dotTopLeft.x, dotTopLeft.y, dotTopLeft.z, 0, 0,
            dotBottomLeft.x, dotBottomLeft.y, dotBottomLeft.z, 0, 0,
            dotBottomRight.x, dotBottomRight.y, dotBottomRight.z, 0, 0,
            dotTopLeft.x, dotTopLeft.y, dotTopLeft.z, 0, 0,
            dotBottomRight.x, dotBottomRight.y, dotBottomRight.z, 0, 0,
            dotTopRight.x, dotTopRight.y, dotTopRight.z, 0, 0,
        };
        glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(dot), dot);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    void uploadSubtitleIfNeeded() {
        std::lock_guard<std::mutex> lock(gSubtitleMutex);
        if (!gSubtitleDirty) return;
        glBindTexture(GL_TEXTURE_2D, subtitleTexture_);
        if (!gSubtitlePixels.empty()) {
            const int width = gSubtitleWidth.load();
            const int height = gSubtitleHeight.load();
            if (width == subtitleTextureWidth_ && height == subtitleTextureHeight_) {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                                GL_RGBA, GL_UNSIGNED_BYTE, gSubtitlePixels.data());
            } else {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                             GL_RGBA, GL_UNSIGNED_BYTE, gSubtitlePixels.data());
                subtitleTextureWidth_ = width;
                subtitleTextureHeight_ = height;
            }
        }
        gSubtitleDirty = false;
    }

    void shutdown() noexcept {
        if (shutdownComplete_) return;
        shutdownComplete_ = true;
        videoSurface_.destroy(env_);
        gPassthroughSupported.store(false);
        if (passthroughRunning_ && passthroughLayer_ && pausePassthroughLayer_) {
            pausePassthroughLayer_(passthroughLayer_);
        }
        if (passthroughRunning_ && passthrough_ && pausePassthrough_) {
            pausePassthrough_(passthrough_);
        }
        passthroughRunning_ = false;
        if (passthroughLayer_ && destroyPassthroughLayer_) {
            destroyPassthroughLayer_(passthroughLayer_);
        }
        if (passthrough_ && destroyPassthrough_) destroyPassthrough_(passthrough_);
        passthroughLayer_ = XR_NULL_HANDLE;
        passthrough_ = XR_NULL_HANDLE;
        if (sessionRunning_ && session_) xrEndSession(session_);
        sessionRunning_ = false;
        for (auto& chain : swapchains_) if (chain.handle) xrDestroySwapchain(chain.handle);
        for (auto& aimSpace : aimSpaces_) if (aimSpace) xrDestroySpace(aimSpace);
        if (space_) xrDestroySpace(space_);
        if (session_) xrDestroySession(session_);
        if (actionSet_) xrDestroyActionSet(actionSet_);
        if (instance_) xrDestroyInstance(instance_);
        swapchains_.clear();
        aimSpaces_.fill(XR_NULL_HANDLE);
        space_ = XR_NULL_HANDLE;
        session_ = XR_NULL_HANDLE;
        actionSet_ = XR_NULL_HANDLE;
        aimAction_ = XR_NULL_HANDLE;
        selectAction_ = XR_NULL_HANDLE;
        thumbstickAction_ = XR_NULL_HANDLE;
        instance_ = XR_NULL_HANDLE;
        if (videoProgram_) glDeleteProgram(videoProgram_);
        if (adaptiveAnalysisProgram_) glDeleteProgram(adaptiveAnalysisProgram_);
        if (subtitleProgram_) glDeleteProgram(subtitleProgram_);
        if (pointerProgram_) glDeleteProgram(pointerProgram_);
        if (videoTexture_) glDeleteTextures(1, &videoTexture_);
        if (subtitleTexture_) glDeleteTextures(1, &subtitleTexture_);
        if (adaptiveAnalysisTexture_) glDeleteTextures(1, &adaptiveAnalysisTexture_);
        if (framebuffer_) glDeleteFramebuffers(1, &framebuffer_);
        if (adaptiveAnalysisFramebuffer_) glDeleteFramebuffers(
            1, &adaptiveAnalysisFramebuffer_);
        if (vertexBuffer_) glDeleteBuffers(1, &vertexBuffer_);
        if (vertexArray_) glDeleteVertexArrays(1, &vertexArray_);
        videoProgram_ = adaptiveAnalysisProgram_ =
            subtitleProgram_ = pointerProgram_ = 0;
        videoTexture_ = subtitleTexture_ = adaptiveAnalysisTexture_ = 0;
        framebuffer_ = adaptiveAnalysisFramebuffer_ =
            vertexBuffer_ = vertexArray_ = 0;
        egl_.destroy();
    }
};

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeRun(
        JNIEnv* env,
        jclass,
        jobject activity,
        jint stereoMode,
        jfloat renderResolutionScale) {
    gStop.store(false);
    try {
        Renderer renderer(env, activity, stereoMode, renderResolutionScale);
        renderer.run();
    } catch (const std::exception& error) {
        LOGE("OpenXR failure: %s", error.what());
        jclass cls = env->GetObjectClass(activity);
        jmethodID callback = env->GetMethodID(cls, "onNativeExit", "(Ljava/lang/String;)V");
        jstring text = env->NewStringUTF(error.what());
        env->CallVoidMethod(activity, callback, text);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(cls);
    } catch (...) {
        constexpr const char* reason = "Unknown native rendering failure";
        LOGE("%s", reason);
        jclass cls = env->GetObjectClass(activity);
        jmethodID callback = env->GetMethodID(cls, "onNativeExit", "(Ljava/lang/String;)V");
        jstring text = env->NewStringUTF(reason);
        env->CallVoidMethod(activity, callback, text);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(cls);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetResumed(JNIEnv*, jclass, jboolean resumed) {
    gResumed.store(resumed == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetMetaGlassesFov(
    JNIEnv*, jclass, jboolean enabled) {
    gMetaGlassesFovEnabled.store(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetPassthrough(
        JNIEnv*, jclass, jboolean enabled, jfloat brightness) {
    gPassthroughRequested.store(enabled == JNI_TRUE);
    if (std::isfinite(brightness)) {
        gPassthroughBrightness.store(std::clamp(brightness, 0.0f, 1.0f));
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeIsPassthroughSupported(JNIEnv*, jclass) {
    return gPassthroughSupported.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeRequestStop(JNIEnv*, jclass) {
    gStop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetVideoAspect(JNIEnv*, jclass, jfloat aspect) {
    if (std::isfinite(aspect) && aspect > 0.1f) gAspect.store(aspect);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetScreenTransform(
    JNIEnv*, jclass, jfloat x, jfloat y, jfloat z, jfloat halfHeight) {
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z) ||
        !std::isfinite(halfHeight)) return;
    Vec3 center{x, y, z};
    const float radius = length(center);
    if (radius < 0.0001f) center = {0, 0, -2.4f};
    else center = center * (std::clamp(radius, 0.5f, 8.0f) / radius);
    gScreenX.store(center.x);
    gScreenY.store(center.y);
    gScreenZ.store(center.z);
    gScreenHalfHeight.store(std::clamp(halfHeight, 0.45f, 1.55f));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeGetScreenTransform(JNIEnv* env, jclass) {
    const jfloat values[] = {
        gScreenX.load(),
        gScreenY.load(),
        gScreenZ.load(),
        gScreenHalfHeight.load(),
    };
    jfloatArray result = env->NewFloatArray(4);
    if (result) env->SetFloatArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetStereoMode(JNIEnv*, jclass, jint stereoMode) {
    if (stereoMode >= 0 && stereoMode <= 4) gStereoMode.store(stereoMode);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetVideoRotation(
    JNIEnv*, jclass, jint quarterTurns) {
    const int normalized = (static_cast<int>(quarterTurns) % 4 + 4) % 4;
    gVideoRotationQuarterTurns.store(normalized);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetVideoProjection(
    JNIEnv*, jclass, jint projection) {
    if (projection >= 0 && projection <= 2) {
        gVideoProjection.store(projection);
        if (projection != 0) {
            gImmersiveMenuAnchorGeneration.fetch_add(1);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetImmersiveOrientation(
    JNIEnv*, jclass, jfloat yawDegrees, jfloat pitchDegrees) {
    if (std::isfinite(yawDegrees)) {
        float normalizedYaw = std::fmod(static_cast<float>(yawDegrees), 360.0f);
        if (normalizedYaw > 180.0f) normalizedYaw -= 360.0f;
        if (normalizedYaw < -180.0f) normalizedYaw += 360.0f;
        gImmersiveYawDegrees.store(normalizedYaw);
    }
    if (std::isfinite(pitchDegrees)) {
        gImmersivePitchDegrees.store(
            std::clamp(static_cast<float>(pitchDegrees), -80.0f, 80.0f));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeRecenterImmersive(
    JNIEnv*, jclass) {
    gImmersiveYawDegrees.store(gLatestHeadYawDegrees.load());
    gImmersivePitchDegrees.store(
        std::clamp(gLatestHeadPitchDegrees.load(), -80.0f, 80.0f));
}

extern "C" JNIEXPORT jfloat JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeGetImmersiveYaw(
    JNIEnv*, jclass) {
    return gImmersiveYawDegrees.load();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeGetImmersivePitch(
    JNIEnv*, jclass) {
    return gImmersivePitchDegrees.load();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetPictureSettings(
    JNIEnv*, jclass, jint pictureMode, jfloat brightness, jfloat exposure,
    jfloat contrast, jfloat saturation, jfloat vibrance,
    jfloat toneMapPeakNits, jfloat sceneBrightness) {
    // Every value here ultimately lands in a fragment-shader uniform that
    // colors the whole frame, and toneMapPeakNits/sceneBrightness are
    // persisted to disk and reapplied on every future launch regardless of
    // which file is playing. std::clamp() does NOT sanitize NaN (NaN
    // comparisons are always false, so the value passes through unchanged),
    // so a single non-finite value here can permanently blacken playback
    // for every file until app storage is cleared. Reject non-finite
    // inputs instead of clamping them, mirroring the isfinite() guards
    // already used by nativeSetVideoColorInfo/nativeSetScreenTransform/etc.
    if (pictureMode >= 0 && pictureMode <= 4) gPictureMode.store(pictureMode);
    if (std::isfinite(brightness)) {
        gPictureBrightness.store(std::clamp(brightness, -0.016f, 0.016f));
    }
    if (std::isfinite(exposure)) {
        gPictureExposure.store(std::clamp(exposure, -3.0f, 3.0f));
    }
    if (std::isfinite(contrast)) {
        gPictureContrast.store(std::clamp(contrast, 0.6f, 1.4f));
    }
    if (std::isfinite(saturation)) {
        gPictureSaturation.store(std::clamp(saturation, 0.7f, 1.3f));
    }
    if (std::isfinite(vibrance)) {
        gPictureVibrance.store(std::clamp(vibrance, -1.0f, 1.0f));
    }
    if (std::isfinite(toneMapPeakNits)) {
        const float clampedPeakNits =
            std::clamp(static_cast<float>(toneMapPeakNits), 50.0f, 5000.0f);
        const float previousPeakNits = gToneMapPeakNits.exchange(clampedPeakNits);
        if (std::abs(previousPeakNits - clampedPeakNits) > 0.1f) {
            gAdaptiveToneMapResetGeneration.fetch_add(1);
        }
        // ITU-R BT.2390 extended HLG display-gamma model for peak
        // luminances outside the usual reference-display range.
        gHlgSystemGamma.store(
            1.2f * std::pow(1.111f, std::log2(clampedPeakNits / 1000.0f)));
    }
    if (std::isfinite(sceneBrightness)) {
        gSceneBrightness.store(std::clamp(sceneBrightness, 0.0f, 1.0f));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetAdaptiveToneMapping(
    JNIEnv*, jclass, jboolean enabled, jfloat minimumNits,
    jfloat maximumNits, jfloat priority, jint mode) {
    if (!std::isfinite(minimumNits) || !std::isfinite(maximumNits) ||
        !std::isfinite(priority)) return;
    float minimum = std::clamp(static_cast<float>(minimumNits), 50.0f, 5000.0f);
    float maximum = std::clamp(static_cast<float>(maximumNits), 50.0f, 5000.0f);
    if (minimum > maximum) std::swap(minimum, maximum);
    gAdaptiveToneMapMinNits.store(minimum);
    gAdaptiveToneMapMaxNits.store(maximum);
    gAdaptiveToneMapPriority.store(std::clamp(static_cast<float>(priority), 0.0f, 1.0f));
    gAdaptiveToneMapMode.store(mode == 0 ? 0 : 1);
    gAdaptiveToneMapEnabled.store(enabled == JNI_TRUE);
    if (enabled != JNI_TRUE) {
        gAdaptiveToneMapEffectiveNits.store(gToneMapPeakNits.load());
    }
    gAdaptiveToneMapResetGeneration.fetch_add(1);
    LOGI(
        "Adaptive HDR: %s, range %.0f-%.0f nits, priority=%.0f%%, response=%s",
        enabled == JNI_TRUE ? "on" : "off",
        minimum,
        maximum,
        gAdaptiveToneMapPriority.load() * 100.0f,
        mode == 0 ? "Cinema" : "Smooth");
}

extern "C" JNIEXPORT jfloat JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeGetAdaptiveToneMapTarget(
    JNIEnv*, jclass) {
    return gAdaptiveToneMapEffectiveNits.load();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetCinemaSettings(
    JNIEnv*, jclass, jfloat toneDepth, jfloat midtoneEv,
    jfloat colorIntensity) {
    // See nativeSetPictureSettings above: these are global, persisted, and
    // applied to every file, so a non-finite value must never reach the
    // shader uniforms or get echoed back into SharedPreferences.
    if (std::isfinite(toneDepth)) {
        gCinemaToneDepth.store(std::clamp(toneDepth, 0.0f, 1.0f));
    }
    if (std::isfinite(midtoneEv)) {
        gCinemaMidtoneEv.store(std::clamp(midtoneEv, -1.0f, 1.0f));
    }
    if (std::isfinite(colorIntensity)) {
        gCinemaColorIntensity.store(std::clamp(colorIntensity, 0.4f, 1.6f));
    }
    LOGI("Display profile: Cinema, depth=%.3f, midtones=%+.3f EV, color=%.3f",
         gCinemaToneDepth.load(),
         gCinemaMidtoneEv.load(),
         gCinemaColorIntensity.load());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetVideoColorInfo(
    JNIEnv*, jclass, jint transfer, jint primaries, jfloat sourcePeakNits,
    jfloat maxFallNits, jint gamutMappingMode) {
    if (transfer >= 0 && transfer <= 2) gVideoTransfer.store(transfer);
    if (primaries >= 0 && primaries <= 1) gVideoPrimaries.store(primaries);
    if (std::isfinite(sourcePeakNits)) {
        gHdrSourcePeakNits.store(std::clamp(sourcePeakNits, 100.0f, 10000.0f));
    }
    if (std::isfinite(maxFallNits)) {
        gHdrMaxFallNits.store(std::clamp(maxFallNits, 0.0f, 10000.0f));
    }
    if (gamutMappingMode >= 0 && gamutMappingMode <= 1) {
        gGamutMappingMode.store(gamutMappingMode);
    }
    gAdaptiveToneMapResetGeneration.fetch_add(1);
    LOGI(
        "Color pipeline: transfer=%d primaries=%d sourcePeak=%.0f MaxFALL=%.0f gamut=%s",
        gVideoTransfer.load(),
        gVideoPrimaries.load(),
        gHdrSourcePeakNits.load(),
        gHdrMaxFallNits.load(),
        gGamutMappingMode.load() == 1 ? "perceptual" : "clip");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetSwapEyes(JNIEnv*, jclass, jboolean swapped) {
    gSwapEyes.store(swapped == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetVideoAvailable(JNIEnv*, jclass, jboolean available) {
    gHasVideo.store(available == JNI_TRUE);
    gAdaptiveToneMapResetGeneration.fetch_add(1);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetImmersivePlaybackUi(
    JNIEnv*, jclass, jboolean enabled) {
    const bool nextEnabled = enabled == JNI_TRUE;
    const bool previousEnabled =
        gImmersivePlaybackUi.exchange(nextEnabled);
    if (!previousEnabled && nextEnabled) {
        gImmersiveMenuAnchorGeneration.fetch_add(1);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetMenuInteractive(JNIEnv*, jclass, jboolean interactive) {
    const bool nextInteractive = interactive == JNI_TRUE;
    const bool previousInteractive =
        gMenuInteractive.exchange(nextInteractive);
    if (!previousInteractive && nextInteractive) {
        gImmersiveMenuAnchorGeneration.fetch_add(1);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetSubtitleDepth(
    JNIEnv*, jclass, jfloat depthMeters) {
    if (std::isfinite(depthMeters)) {
        gSubtitleDepthMeters.store(std::clamp(depthMeters, 0.0f, 0.50f));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetSubtitle3dEnabled(
    JNIEnv*,
    jclass,
    jboolean enabled) {
    gSubtitle3dEnabled.store(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetTextSubtitleAppearance(
    JNIEnv*,
    jclass,
    jfloat red,
    jfloat green,
    jfloat blue,
    jfloat brightness,
    jfloat scale,
    jfloat horizontalPosition,
    jfloat verticalPosition) {
    if (std::isfinite(red)) gTextSubtitleRed.store(std::clamp(red, 0.0f, 1.0f));
    if (std::isfinite(green)) gTextSubtitleGreen.store(std::clamp(green, 0.0f, 1.0f));
    if (std::isfinite(blue)) gTextSubtitleBlue.store(std::clamp(blue, 0.0f, 1.0f));
    if (std::isfinite(brightness)) {
        gTextSubtitleBrightness.store(std::clamp(brightness, 0.25f, 1.0f));
    }
    if (std::isfinite(scale)) gTextSubtitleScale.store(std::clamp(scale, 0.6f, 1.8f));
    if (std::isfinite(horizontalPosition)) {
        gTextSubtitleHorizontalPosition.store(
            std::clamp(horizontalPosition, 0.0f, 1.0f));
    }
    if (std::isfinite(verticalPosition)) {
        gTextSubtitleVerticalPosition.store(
            std::clamp(verticalPosition, 0.0f, 1.0f));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetScreenCurvature(
    JNIEnv*, jclass, jfloat curvature) {
    if (std::isfinite(curvature)) {
        gScreenCurvature.store(std::clamp(curvature, 0.0f, 1.0f));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetSubtitle(JNIEnv* env, jclass, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    {
        std::lock_guard<std::mutex> lock(gSubtitleMutex);
        gSubtitleWidth.store(static_cast<int>(info.width));
        gSubtitleHeight.store(static_cast<int>(info.height));
        gSubtitlePixels.resize(info.width * info.height * 4);
        for (uint32_t row = 0; row < info.height; ++row) {
            std::memcpy(gSubtitlePixels.data() + row * info.width * 4,
                        static_cast<uint8_t*>(pixels) + row * info.stride,
                        info.width * 4);
        }
        gSubtitleBitmapMode.store(false);
        gSubtitleDirty = true;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dreamplayer_PlayerActivity_nativeSetBitmapSubtitle(
    JNIEnv* env,
    jclass,
    jobject bitmap,
    jfloat left,
    jfloat top,
    jfloat right,
    jfloat bottom) {
    if (!std::isfinite(left) || !std::isfinite(top) ||
        !std::isfinite(right) || !std::isfinite(bottom) ||
        right <= left || bottom <= top) return;
    AndroidBitmapInfo info{};
    if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    {
        std::lock_guard<std::mutex> lock(gSubtitleMutex);
        gSubtitleWidth.store(static_cast<int>(info.width));
        gSubtitleHeight.store(static_cast<int>(info.height));
        gSubtitlePixels.resize(info.width * info.height * 4);
        for (uint32_t row = 0; row < info.height; ++row) {
            std::memcpy(gSubtitlePixels.data() + row * info.width * 4,
                        static_cast<uint8_t*>(pixels) + row * info.stride,
                        info.width * 4);
        }
        gSubtitleLeft.store(std::clamp(static_cast<float>(left), 0.0f, 1.0f));
        gSubtitleTop.store(std::clamp(static_cast<float>(top), 0.0f, 1.0f));
        gSubtitleRight.store(std::clamp(static_cast<float>(right), 0.0f, 1.0f));
        gSubtitleBottom.store(std::clamp(static_cast<float>(bottom), 0.0f, 1.0f));
        gSubtitleBitmapMode.store(true);
        gSubtitleDirty = true;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}
