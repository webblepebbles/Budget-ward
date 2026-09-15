package game.render;

import game.assets.ObjLoader;
import game.player.Player;
import game.world.Doors;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

public final class VkRenderer implements AutoCloseable {

    private static final int MAX_FRAMES = 2;

    private final long window;
    private volatile boolean framebufferResized;

    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice pd;
    private VkDevice device;
    private VkQueue queue;
    private int queueFamily;
    private VkPhysicalDeviceMemoryProperties memProps;
    private int depthFormat;

    private long swapchain;
    private long[] swapImages = new long[0];
    private long[] swapViews = new long[0];
    private int colorFormat;
    private int swW = 1280, swH = 720;
    private long depthImage, depthMem, depthView;
    private int msaaSamples = VK_SAMPLE_COUNT_4_BIT;
    private long msaaColorImage, msaaColorMem, msaaColorView;
    private int lastImageIdx;

    private long cmdPool;
    private VkCommandBuffer[] cmds;
    private long[] fences = new long[MAX_FRAMES];
    private long[] imageAvail = new long[MAX_FRAMES];
    private long[] renderDone = new long[MAX_FRAMES];
    private int frameIdx;
    private final IntBuffer acquiredIdx = MemoryUtil.memAllocInt(1);

    private long wPosBuf, wPosMem;
    private long wColBuf, wColMem;
    private long wEmiBuf, wEmiMem;
    private int mapOpaqueCount;
    private int mapTransFirst, mapTransCount;
    private int worldVertexCount;

    private static final class DoorSeg {
        int leaf;
        int first, count;
        boolean transparent;
        float cx, cy, cz;
    }

    private final List<DoorSeg> doorSegs = new ArrayList<>();
    private List<Doors.Leaf> doorLeaves = List.of();

    private long hudBuf, hudMem;
    private static final int HUD_MAX_BYTES = 262144;

    private long npcBuf, npcMem;
    private static final int NPC_MAX_BYTES = 524288;
    private final java.nio.ByteBuffer npcScratch = MemoryUtil.memAlloc(NPC_MAX_BYTES);
    private int npcVertexCount;

    public volatile boolean dynamicDirty = true;
    private long frameCount;
    private int lastDynCount = -1;
    private java.util.List<game.world.MapFixes.PLight> allLights = java.util.List.of();
    private static final int ACTIVE_LIGHTS = 56;

    public java.util.List<game.world.Npc> npcList;

    public java.util.List<game.world.MovableBed> beds;

    private long mapPipeline, glassPipeline, mapLayout;
    private long hudPipeline, hudLayout;
    private long descLayout, descPool, descSet, sampler;
    private long lightLayout, lightPool, lightSet;
    private long lightBuf, lightMem;
    private static final int MAX_LIGHTS = 96;
    private int uploadedLightCount;
    private long fontImage, fontMem, fontView;

    private long stagingBuf, stagingMem, stagingSize;

    public VkRenderer(long window) {
        this.window = window;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            createInstance(stack);
            createSurface();
            pickPhysicalDevice(stack);
            createLogicalDevice(stack);
            createCommandSystem(stack);
            createSwapchain(stack);
            createPipelines(stack);

            PointerBuffer hudPb = stack.mallocPointer(1);
            hudMem = createBuffer(HUD_MAX_BYTES,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    hudPb, stack);
            hudBuf = hudPb.get(0);

            PointerBuffer npcPb = stack.mallocPointer(1);
            npcMem = createBuffer(NPC_MAX_BYTES,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    npcPb, stack);
            npcBuf = npcPb.get(0);
        }
    }

    public void requestResize() {
        framebufferResized = true;
    }

    public int swapWidth() {
        return swW;
    }

    public int swapHeight() {
        return swH;
    }

    private void createInstance(MemoryStack stack) {
        VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("Budget Ward"))
                .pEngineName(stack.UTF8("BudgetWardRenderer"))
                .apiVersion(VK_MAKE_VERSION(1, 2, 0));

        PointerBuffer glfwExts = org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions();
        PointerBuffer exts = stack.mallocPointer(glfwExts.remaining() + 1);
        exts.put(glfwExts);
        exts.flip();

        VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(exts);

        PointerBuffer ip = stack.mallocPointer(1);
        check(vkCreateInstance(ci, null, ip), "vkCreateInstance");
        instance = new VkInstance(ip.get(0), ci);
    }

    private void createSurface() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer sp = stack.mallocLong(1);
            check(org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface(instance, window, null, sp),
                    "glfwCreateWindowSurface");
            surface = sp.get(0);
        }
    }

    private void pickPhysicalDevice(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");

        VkPhysicalDevice best = null;
        int bestScore = -1;
        for (int i = 0; i < devices.remaining(); i++) {
            VkPhysicalDevice d = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(d, props);
            int score = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 100 : 10;
            score += props.limits().maxImageDimension2D() > 4096 ? 5 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        if (best == null) {
            throw new RuntimeException("No Vulkan physical device found");
        }
        pd = best;

        memProps = VkPhysicalDeviceMemoryProperties.calloc();
        vkGetPhysicalDeviceMemoryProperties(pd, memProps);

        depthFormat = findSupportedDepthFormat(stack);
    }

    private int findSupportedDepthFormat(MemoryStack stack) {
        int[] candidates = {VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT};
        VkFormatProperties props = VkFormatProperties.calloc(stack);
        for (int f : candidates) {
            vkGetPhysicalDeviceFormatProperties(pd, f, props);
            if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                return f;
            }
        }
        throw new RuntimeException("No supported depth format");
    }

    private boolean queueFamilySupportsSurface(int family, MemoryStack stack) {
        IntBuffer supported = stack.mallocInt(1);
        KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(pd, family, surface, supported);
        return supported.get(0) == VK_TRUE;
    }

    private void createLogicalDevice(MemoryStack stack) {
        IntBuffer qCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, qCount, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(qCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, qCount, families);

        queueFamily = -1;
        for (int i = 0; i < families.remaining(); i++) {
            int flags = families.get(i).queueFlags();
            if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0 && queueFamilySupportsSurface(i, stack)) {
                queueFamily = i;
                break;
            }
        }
        if (queueFamily < 0) {
            throw new RuntimeException("No graphics+present queue family");
        }

        VkDeviceQueueCreateInfo.Buffer qInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
        qInfos.get(0)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(stack.floats(1.0f));

        PointerBuffer devExts = stack.mallocPointer(5);
        devExts.put(stack.UTF8("VK_KHR_swapchain"));
        devExts.put(stack.UTF8("VK_KHR_maintenance2"));
        devExts.put(stack.UTF8("VK_KHR_multiview"));
        devExts.put(stack.UTF8("VK_KHR_create_renderpass2"));
        devExts.put(stack.UTF8("VK_KHR_dynamic_rendering"));
        devExts.flip();

        VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(qInfos)
                .ppEnabledExtensionNames(devExts)
                .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack));

        PointerBuffer dp = stack.mallocPointer(1);
        check(vkCreateDevice(pd, dci, null, dp), "vkCreateDevice");
        device = new VkDevice(dp.get(0), pd, dci);
        PointerBuffer qp = stack.mallocPointer(1);
        vkGetDeviceQueue(device, queueFamily, 0, qp);
        queue = new VkQueue(qp.get(0), device);
    }

    private void createSwapchain(MemoryStack stack) {
        VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
        check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, caps), "surface caps");

        IntBuffer fmtCount = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, fmtCount, null),
                "surface format count");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(fmtCount.get(0), stack);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, fmtCount, formats),
                "surface formats");

        colorFormat = formats.get(0).format();
        int colorSpace = formats.get(0).colorSpace();
        for (int i = 0; i < formats.remaining(); i++) {
            VkSurfaceFormatKHR f = formats.get(i);
            if (f.format() == VK_FORMAT_B8G8R8A8_UNORM
                    && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                colorFormat = VK_FORMAT_B8G8R8A8_UNORM;
                colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
                break;
            }
        }

        IntBuffer pmCount = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, pmCount, null),
                "present mode count");
        IntBuffer presentModes = stack.mallocInt(pmCount.get(0));
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, pmCount, presentModes),
                "present modes");
        int presentMode = VK_PRESENT_MODE_FIFO_KHR;
        for (int i = 0; i < presentModes.remaining(); i++) {
            if (presentModes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
                break;
            }
        }

        int imgCount = caps.minImageCount() + 1;
        if (caps.maxImageCount() > 0 && imgCount > caps.maxImageCount()) {
            imgCount = caps.maxImageCount();
        }

        VkExtent2D extent = caps.currentExtent();
        int width = extent.width();
        int height = extent.height();
        if (width == 0xFFFFFFFF) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, w, h);
            width = w.get(0);
            height = h.get(0);
        }
        swW = width;
        swH = height;
        final int extW = width, extH = height;

        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
        vkGetPhysicalDeviceProperties(pd, props);
        int supported = props.limits().framebufferColorSampleCounts()
                & props.limits().framebufferDepthSampleCounts();
        if ((supported & VK_SAMPLE_COUNT_4_BIT) != 0) {
            msaaSamples = VK_SAMPLE_COUNT_4_BIT;
        } else if ((supported & VK_SAMPLE_COUNT_2_BIT) != 0) {
            msaaSamples = VK_SAMPLE_COUNT_2_BIT;
        } else {
            msaaSamples = VK_SAMPLE_COUNT_1_BIT;
        }

        VkSwapchainCreateInfoKHR sci = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(surface)
                .minImageCount(imgCount)
                .imageFormat(colorFormat)
                .imageColorSpace(colorSpace)
                .imageExtent(e -> e.width(extW).height(extH))
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(caps.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(presentMode)
                .clipped(true)
                .oldSwapchain(VK_NULL_HANDLE);

        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateSwapchainKHR(device, sci, null, lp), "vkCreateSwapchainKHR");
        swapchain = lp.get(0);

        IntBuffer imgCount2 = stack.mallocInt(1);
        check(vkGetSwapchainImagesKHR(device, swapchain, imgCount2, null),
                "swapchain image count");
        LongBuffer images = stack.mallocLong(imgCount2.get(0));
        check(vkGetSwapchainImagesKHR(device, swapchain, imgCount2, images),
                "swapchain images");
        swapImages = new long[images.remaining()];
        swapViews = new long[images.remaining()];
        for (int i = 0; i < images.remaining(); i++) {
            swapImages[i] = images.get(i);
            swapViews[i] = createImageView(swapImages[i], colorFormat, VK_IMAGE_ASPECT_COLOR_BIT, stack);
        }

        long[] outImg = new long[1];
        depthMem = createImage(swW, swH, depthFormat,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, msaaSamples, outImg, stack);
        depthImage = outImg[0];
        depthView = createImageView(depthImage, depthFormat, depthAspectMask(), stack);

        if (msaaSamples != VK_SAMPLE_COUNT_1_BIT) {
            msaaColorMem = createImage(swW, swH, colorFormat,
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT,
                    msaaSamples, outImg, stack);
            msaaColorImage = outImg[0];
            msaaColorView = createImageView(msaaColorImage, colorFormat, VK_IMAGE_ASPECT_COLOR_BIT, stack);
        }
        transitionDepthToAttachment(stack);
    }

    private void destroyMsaaColor() {
        if (msaaColorView != 0) {
            vkDestroyImageView(device, msaaColorView, null);
            msaaColorView = 0;
        }
        if (msaaColorImage != 0) {
            vkDestroyImage(device, msaaColorImage, null);
            msaaColorImage = 0;
        }
        if (msaaColorMem != 0) {
            vkFreeMemory(device, msaaColorMem, null);
            msaaColorMem = 0;
        }
    }

    private int depthAspectMask() {
        return depthFormat == VK_FORMAT_D32_SFLOAT
                ? VK_IMAGE_ASPECT_DEPTH_BIT
                : VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
    }

    private long createImageView(long image, int format, int aspect, MemoryStack stack) {
        VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
                .subresourceRange(r -> r.aspectMask(aspect)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1));
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateImageView(device, ci, null, lp), "vkCreateImageView");
        return lp.get(0);
    }

    private void transitionDepthToAttachment(MemoryStack stack) {
        long fence = createFence(stack);
        vkResetFences(device, fence);
        vkResetCommandBuffer(cmds[0], 0);
        VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        vkBeginCommandBuffer(cmds[0], bi);
        transitionImage(cmds[0], depthImage, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, depthAspectMask());
        if (msaaColorImage != 0) {
            transitionImage(cmds[0], msaaColorImage, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);
        }
        vkEndCommandBuffer(cmds[0]);
        VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmds[0]));
        check(vkQueueSubmit(queue, si, fence), "depth transition submit");
        vkWaitForFences(device, fence, true, 0xFFFFFFFFFFFFFFFFL);
        vkDestroyFence(device, fence, null);
    }

    private void recreateSwapchain(MemoryStack stack) {
        vkDeviceWaitIdle(device);
        for (long v : swapViews) {
            vkDestroyImageView(device, v, null);
        }
        vkDestroyImageView(device, depthView, null);
        vkDestroyImage(device, depthImage, null);
        vkFreeMemory(device, depthMem, null);
        destroyMsaaColor();
        vkDestroySwapchainKHR(device, swapchain, null);
        createSwapchain(stack);
    }

    private void createCommandSystem(MemoryStack stack) {
        VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamily);
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateCommandPool(device, pci, null, lp), "vkCreateCommandPool");
        cmdPool = lp.get(0);

        VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(cmdPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(MAX_FRAMES);
        PointerBuffer pb = stack.mallocPointer(MAX_FRAMES);
        check(vkAllocateCommandBuffers(device, cai, pb), "vkAllocateCommandBuffers");
        cmds = new VkCommandBuffer[MAX_FRAMES];
        for (int i = 0; i < MAX_FRAMES; i++) {
            cmds[i] = new VkCommandBuffer(pb.get(i), device);
            fences[i] = createFence(stack);
            imageAvail[i] = createSemaphore(stack);
            renderDone[i] = createSemaphore(stack);
        }
    }

    private long createFence(MemoryStack stack) {
        VkFenceCreateInfo ci = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK_FENCE_CREATE_SIGNALED_BIT);
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateFence(device, ci, null, lp), "vkCreateFence");
        return lp.get(0);
    }

    private long createSemaphore(MemoryStack stack) {
        VkSemaphoreCreateInfo ci = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateSemaphore(device, ci, null, lp), "vkCreateSemaphore");
        return lp.get(0);
    }

    private static final int SPIRV_MAGIC_0 = 0x03;

    private static ByteBuffer loadShader(String resource, int kind) {
        String spvPath = resource + ".spv";
        try (var in = VkRenderer.class.getResourceAsStream(spvPath)) {
            if (in != null) {
                byte[] bytes = in.readAllBytes();
                if (bytes.length >= 8 && (bytes[0] & 0xFF) == SPIRV_MAGIC_0) {
                    ByteBuffer out = MemoryUtil.memAlloc(bytes.length);
                    out.put(bytes);
                    out.flip();
                    return out;
                }
            }
        } catch (Exception ignored) {

        }
        return compileShaderRuntime(resource, kind);
    }

    private static ByteBuffer compileShaderRuntime(String resource, int kind) {
        String src;
        try (var in = VkRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new RuntimeException("Missing shader resource: " + resource);
            }
            src = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read shader " + resource, e);
        }
        long compiler = shaderc_compiler_initialize();
        long opts = shaderc_compile_options_initialize();
        long result = shaderc_compile_into_spv(compiler, src, kind, resource, "main", opts);
        boolean ok = result != 0
                && shaderc_result_get_compilation_status(result) == shaderc_compilation_status_success;
        if (!ok) {
            String log = result != 0 ? shaderc_result_get_error_message(result) : "shaderc returned null";
            if (result != 0) {
                shaderc_result_release(result);
            }
            shaderc_compile_options_release(opts);
            shaderc_compiler_release(compiler);
            throw new RuntimeException("Shader compile failed for " + resource + ":\n" + log);
        }
        ByteBuffer bytes = shaderc_result_get_bytes(result);
        ByteBuffer out = MemoryUtil.memAlloc(bytes.remaining());
        out.put(bytes);
        out.flip();
        shaderc_result_release(result);
        shaderc_compile_options_release(opts);
        shaderc_compiler_release(compiler);
        return out;
    }

    private long createShaderModule(ByteBuffer spv, MemoryStack stack) {
        VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spv);
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateShaderModule(device, ci, null, lp), "vkCreateShaderModule");
        MemoryUtil.memFree(spv);
        return lp.get(0);
    }

    private long createPipelineLayout(int pushSize, long setLayout, MemoryStack stack) {
        VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
        ranges.get(0)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .offset(0)
                .size(pushSize);
        VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pPushConstantRanges(ranges);
        if (setLayout != VK_NULL_HANDLE) {
            ci.setLayoutCount(1)
              .pSetLayouts(stack.longs(setLayout));
        }
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device, ci, null, lp), "vkCreatePipelineLayout");
        return lp.get(0);
    }

    private void createPipelines(MemoryStack stack) {

        VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

        VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .lineWidth(1.0f);

        VkPipelineMultisampleStateCreateInfo msMsaa = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(msaaSamples);
        VkPipelineMultisampleStateCreateInfo msHud = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

        VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

        VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1);

        long vert = createShaderModule(loadShader("/shaders/map.vert", shaderc_glsl_vertex_shader), stack);
        long frag = createShaderModule(loadShader("/shaders/map.frag", shaderc_glsl_fragment_shader), stack);

        VkDescriptorSetLayoutBinding.Buffer llb = VkDescriptorSetLayoutBinding.calloc(1, stack);
        llb.get(0)
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        VkDescriptorSetLayoutCreateInfo lslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(llb);
        LongBuffer llp = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device, lslci, null, llp), "vkCreateDescriptorSetLayout(lights)");
        lightLayout = llp.get(0);
        mapLayout = createPipelineLayout(160, lightLayout, stack);

        VkVertexInputBindingDescription.Buffer binds = VkVertexInputBindingDescription.calloc(3, stack);
        binds.get(0).binding(0).stride(12).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        binds.get(1).binding(1).stride(16).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        binds.get(2).binding(2).stride(4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);
        attrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        attrs.get(1).location(1).binding(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(0);
        attrs.get(2).location(2).binding(2).format(VK_FORMAT_R32_UINT).offset(0);

        VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(binds)
                .pVertexAttributeDescriptions(attrs);

        VkPipelineDepthStencilStateCreateInfo dsOpaque = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(true)
                .depthWriteEnable(true)
                .depthCompareOp(VK_COMPARE_OP_LESS);

        VkPipelineColorBlendAttachmentState.Buffer mapAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        mapAtt.get(0)
                .blendEnable(false)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        VkPipelineColorBlendStateCreateInfo mapCb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(mapAtt);

        mapPipeline = createGraphicsPipeline(vert, frag, mapLayout, vi, ia, vp, rs, dsOpaque, msMsaa, mapCb, dyn,
                depthFormat, colorFormat, stack);

        VkPipelineDepthStencilStateCreateInfo dsGlass = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(true)
                .depthWriteEnable(false)
                .depthCompareOp(VK_COMPARE_OP_LESS);

        VkPipelineColorBlendAttachmentState.Buffer glassAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        glassAtt.get(0)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .alphaBlendOp(VK_BLEND_OP_ADD)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        VkPipelineColorBlendStateCreateInfo glassCb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(glassAtt);

        glassPipeline = createGraphicsPipeline(vert, frag, mapLayout, vi, ia, vp, rs, dsGlass, msMsaa, glassCb, dyn,
                depthFormat, colorFormat, stack);

        vkDestroyShaderModule(device, vert, null);
        vkDestroyShaderModule(device, frag, null);

        long hudVert = createShaderModule(loadShader("/shaders/hud.vert", shaderc_glsl_vertex_shader), stack);
        long hudFrag = createShaderModule(loadShader("/shaders/hud.frag", shaderc_glsl_fragment_shader), stack);

        VkDescriptorSetLayoutBinding.Buffer lb = VkDescriptorSetLayoutBinding.calloc(1, stack);
        lb.get(0)
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
                .pImmutableSamplers(null);
        VkDescriptorSetLayoutCreateInfo slci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(lb);
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device, slci, null, lp), "vkCreateDescriptorSetLayout");
        descLayout = lp.get(0);
        hudLayout = createPipelineLayout(8, descLayout, stack);

        VkVertexInputBindingDescription.Buffer hudBinds = VkVertexInputBindingDescription.calloc(3, stack);
        hudBinds.get(0).binding(0).stride(8).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        hudBinds.get(1).binding(1).stride(8).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        hudBinds.get(2).binding(2).stride(16).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        VkVertexInputAttributeDescription.Buffer hudAttrs = VkVertexInputAttributeDescription.calloc(3, stack);
        hudAttrs.get(0).location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
        hudAttrs.get(1).location(1).binding(1).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
        hudAttrs.get(2).location(2).binding(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(0);

        VkPipelineVertexInputStateCreateInfo hudVi = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(hudBinds)
                .pVertexAttributeDescriptions(hudAttrs);

        VkPipelineDepthStencilStateCreateInfo hudDs = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(false)
                .depthWriteEnable(false);

        VkPipelineColorBlendAttachmentState.Buffer hudAtt = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        hudAtt.get(0)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .alphaBlendOp(VK_BLEND_OP_ADD)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        VkPipelineColorBlendStateCreateInfo hudCb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(hudAtt);

        hudPipeline = createGraphicsPipeline(hudVert, hudFrag, hudLayout, hudVi, ia, vp, rs, hudDs, msHud, hudCb, dyn,
                VK_FORMAT_UNDEFINED, colorFormat, stack);
        vkDestroyShaderModule(device, hudVert, null);
        vkDestroyShaderModule(device, hudFrag, null);

        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
        poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
        VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(1)
                .pPoolSizes(poolSizes);
        check(vkCreateDescriptorPool(device, dpci, null, lp), "vkCreateDescriptorPool");
        descPool = lp.get(0);

        VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descPool)
                .pSetLayouts(stack.longs(descLayout));
        LongBuffer dsp = stack.mallocLong(1);
        check(vkAllocateDescriptorSets(device, dsai, dsp), "vkAllocateDescriptorSets");
        descSet = dsp.get(0);

        VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .maxAnisotropy(1.0f)
                .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                .unnormalizedCoordinates(false);
        check(vkCreateSampler(device, sci, null, lp), "vkCreateSampler");
        sampler = lp.get(0);

        VkDescriptorPoolSize.Buffer lps = VkDescriptorPoolSize.calloc(1, stack);
        lps.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
        VkDescriptorPoolCreateInfo lpci = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(1)
                .pPoolSizes(lps);
        check(vkCreateDescriptorPool(device, lpci, null, lp), "vkCreateDescriptorPool(lights)");
        lightPool = lp.get(0);
        VkDescriptorSetAllocateInfo ldsai = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(lightPool)
                .pSetLayouts(stack.longs(lightLayout));
        LongBuffer ldsp = stack.mallocLong(1);
        check(vkAllocateDescriptorSets(device, ldsai, ldsp), "vkAllocateDescriptorSets(lights)");
        lightSet = ldsp.get(0);

        PointerBuffer lpb = stack.mallocPointer(1);
        lightMem = createBuffer(MAX_LIGHTS * 2 * 16,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                lpb, stack);
        lightBuf = lpb.get(0);

        VkDescriptorBufferInfo.Buffer ldi = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(lightBuf).offset(0).range(MAX_LIGHTS * 2 * 16);
        VkWriteDescriptorSet.Buffer lwd = VkWriteDescriptorSet.calloc(1, stack);
        lwd.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(lightSet)
                .dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(ldi);
        vkUpdateDescriptorSets(device, lwd, null);
    }

    private long createGraphicsPipeline(long vert, long frag, long layout,
                                        VkPipelineVertexInputStateCreateInfo vi,
                                        VkPipelineInputAssemblyStateCreateInfo ia,
                                        VkPipelineViewportStateCreateInfo vp,
                                        VkPipelineRasterizationStateCreateInfo rs,
                                        VkPipelineDepthStencilStateCreateInfo ds,
                                        VkPipelineMultisampleStateCreateInfo ms,
                                        VkPipelineColorBlendStateCreateInfo cb,
                                        VkPipelineDynamicStateCreateInfo dyn,
                                        int depthFmt, int colorFmt,
                                        MemoryStack stack) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
        stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));

        VkPipelineRenderingCreateInfo pri = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                .colorAttachmentCount(1)
                .pColorAttachmentFormats(stack.ints(colorFmt))
                .depthAttachmentFormat(depthFmt);

        VkGraphicsPipelineCreateInfo.Buffer gpci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        gpci.get(0)
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pNext(pri)
                .pStages(stages)
                .pVertexInputState(vi)
                .pInputAssemblyState(ia)
                .pViewportState(vp)
                .pRasterizationState(rs)
                .pMultisampleState(ms)
                .pDepthStencilState(ds)
                .pColorBlendState(cb)
                .pDynamicState(dyn)
                .layout(layout);

        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gpci, null, lp),
                "vkCreateGraphicsPipelines");
        return lp.get(0);
    }

    private int findMemoryType(int typeBits, int wanted) {
        for (int i = 0; i < 32; i++) {
            if ((typeBits & (1 << i)) != 0
                    && (memProps.memoryTypes(i).propertyFlags() & wanted) == wanted) {
                return i;
            }
        }
        return -1;
    }

    private long createBuffer(long size, int usage, int wantedProps, PointerBuffer outBuf, MemoryStack stack) {
        VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateBuffer(device, bci, null, lp), "vkCreateBuffer");
        long buf = lp.get(0);
        VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, buf, reqs);

        int idx = findMemoryType(reqs.memoryTypeBits(), wantedProps);
        if (idx < 0) {
            idx = findMemoryType(reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        }
        if (idx < 0) {
            throw new RuntimeException("No usable Vulkan memory type for buffer (wanted=0x"
                    + Integer.toHexString(wantedProps) + ")");
        }
        VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(idx);
        check(vkAllocateMemory(device, ai, null, lp), "vkAllocateMemory");
        long mem = lp.get(0);
        check(vkBindBufferMemory(device, buf, mem, 0), "vkBindBufferMemory");
        outBuf.put(0, buf);
        return mem;
    }

    private long createImage(int w, int h, int format, int usage, long[] outImage, MemoryStack stack) {
        return createImage(w, h, format, usage, VK_SAMPLE_COUNT_1_BIT, outImage, stack);
    }

    private long createImage(int w, int h, int format, int usage, int samples, long[] outImage, MemoryStack stack) {
        VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .extent(e -> e.width(w).height(h).depth(1))
                .mipLevels(1)
                .arrayLayers(1)
                .samples(samples)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateImage(device, ici, null, lp), "vkCreateImage");
        long img = lp.get(0);
        VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(device, img, reqs);

        int idx = findMemoryType(reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (idx < 0) {
            idx = findMemoryType(reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        }
        if (idx < 0) {
            throw new RuntimeException("No usable Vulkan memory type for image");
        }
        VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(idx);
        check(vkAllocateMemory(device, ai, null, lp), "vkAllocateMemory");
        long mem = lp.get(0);
        check(vkBindImageMemory(device, img, mem, 0), "vkBindImageMemory");
        outImage[0] = img;
        return mem;
    }

    private interface Recorder {
        void record(VkCommandBuffer cmd);
    }

    private long ensureStaging(long size, MemoryStack stack) {
        if (stagingSize >= size) {
            return stagingBuf;
        }
        if (stagingBuf != 0) {
            vkDestroyBuffer(device, stagingBuf, null);
            vkFreeMemory(device, stagingMem, null);
        }
        long realSize = Math.max(size, 1 << 22);
        PointerBuffer pb = stack.mallocPointer(1);
        stagingMem = createBuffer(realSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, pb, stack);
        stagingBuf = pb.get(0);
        stagingSize = realSize;
        return stagingBuf;
    }

    private void submitOneShotRecorder(Recorder recorder, MemoryStack stack) {
        long fence = createFence(stack);
        vkResetFences(device, fence);
        vkResetCommandBuffer(cmds[MAX_FRAMES - 1], 0);
        VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        vkBeginCommandBuffer(cmds[MAX_FRAMES - 1], bi);
        recorder.record(cmds[MAX_FRAMES - 1]);
        vkEndCommandBuffer(cmds[MAX_FRAMES - 1]);
        VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmds[MAX_FRAMES - 1]));
        check(vkQueueSubmit(queue, si, fence), "one-shot submit");
        vkWaitForFences(device, fence, true, 0xFFFFFFFFFFFFFFFFL);
        vkDestroyFence(device, fence, null);
    }

    private void copyBuffer(long src, long dst, long size, MemoryStack stack) {
        submitOneShotRecorder(cmd -> {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(0).dstOffset(0).size(size);
            vkCmdCopyBuffer(cmd, src, dst, region);
        }, stack);
    }

    private long upload(ByteBuffer data, int usage, PointerBuffer outBuf, MemoryStack stack) {
        long size = data.remaining();
        long staging = ensureStaging(size, stack);
        PointerBuffer mapPb = stack.mallocPointer(1);
        check(vkMapMemory(device, stagingMem, 0, stagingSize, 0, mapPb), "vkMapMemory");
        ByteBuffer mapped = mapPb.getByteBuffer(0, (int) stagingSize);
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(mapped), size);
        vkUnmapMemory(device, stagingMem);

        PointerBuffer pb = stack.mallocPointer(1);
        long mem = createBuffer(size, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, pb, stack);
        copyBuffer(staging, pb.get(0), size, stack);
        outBuf.put(0, pb.get(0));
        return mem;
    }

    public void uploadMap(java.util.List<ObjLoader.Model> models, List<Doors.Leaf> leaves) {
        doorLeaves = leaves;
        doorSegs.clear();

        int opaqueVerts = 0;
        int transVerts = 0;
        for (ObjLoader.Model m : models) {
            for (ObjLoader.MeshPart p : m.parts) {
                if (p.isTransparent()) {
                    transVerts += p.vertexCount();
                } else {
                    opaqueVerts += p.vertexCount();
                }
            }
        }
        int doorVerts = 0;
        for (Doors.Leaf leaf : leaves) {
            for (ObjLoader.MeshPart p : leaf.parts()) {
                doorVerts += p.vertexCount();
            }
        }
        int totalVerts = opaqueVerts + transVerts + doorVerts;

        FloatBuffer pos = MemoryUtil.memAllocFloat(totalVerts * 3);
        FloatBuffer col = MemoryUtil.memAllocFloat(totalVerts * 4);
        IntBuffer emi = MemoryUtil.memAllocInt(totalVerts);

        for (ObjLoader.Model m : models) {
            for (ObjLoader.MeshPart p : m.parts) {
                if (p.isTransparent()) {
                    continue;
                }
                boolean emissive = p.material.toLowerCase().contains("emit")
                        || p.material.equalsIgnoreCase("BW_daylight");
                putPart(pos, col, emi, p, emissive);
            }
        }
        for (ObjLoader.Model m : models) {
            for (ObjLoader.MeshPart p : m.parts) {
                if (!p.isTransparent()) {
                    continue;
                }
                boolean emissive = p.material.toLowerCase().contains("emit")
                        || p.material.equalsIgnoreCase("BW_daylight");
                putPart(pos, col, emi, p, emissive);
            }
        }
        mapOpaqueCount = opaqueVerts;
        mapTransFirst = opaqueVerts;
        mapTransCount = transVerts;

        int base = opaqueVerts + transVerts;
        for (int li = 0; li < leaves.size(); li++) {
            Doors.Leaf leaf = leaves.get(li);
            for (ObjLoader.MeshPart p : leaf.parts()) {
                boolean emissive = p.emissive || p.material.toLowerCase().contains("emit");
                int before = (pos.position()) / 3;
                putPart(pos, col, emi, p, emissive);
                DoorSeg seg = new DoorSeg();
                seg.leaf = li;
                seg.first = before;
                seg.count = (pos.position()) / 3 - before;
                seg.transparent = p.isTransparent();
                seg.cx = (p.boundsMin[0] + p.boundsMax[0]) * 0.5f;
                seg.cy = (p.boundsMin[1] + p.boundsMax[1]) * 0.5f;
                seg.cz = (p.boundsMin[2] + p.boundsMax[2]) * 0.5f;
                doorSegs.add(seg);
            }
        }
        worldVertexCount = totalVerts;

        try {
            long aoT0 = System.currentTimeMillis();
            float[] ao = AmbientOcclusion.compute(pos, emi, totalVerts);
            AmbientOcclusion.applyToColors(col, ao, totalVerts);
            System.out.println("Baked AO over " + totalVerts + " verts in "
                    + (System.currentTimeMillis() - aoT0) + " ms");
        } catch (OutOfMemoryError | RuntimeException e) {
            System.out.println("AO bake skipped (" + e + "); lighting unaffected");
        }

        pos.flip();
        col.flip();
        emi.flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pb = stack.mallocPointer(1);
            ByteBuffer posB = MemoryUtil.memByteBuffer(MemoryUtil.memAddress(pos), totalVerts * 3 * 4);
            wPosMem = upload(posB, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, pb, stack);
            wPosBuf = pb.get(0);
            ByteBuffer colB = MemoryUtil.memByteBuffer(MemoryUtil.memAddress(col), totalVerts * 4 * 4);
            wColMem = upload(colB, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, pb, stack);
            wColBuf = pb.get(0);
            ByteBuffer emiB = MemoryUtil.memByteBuffer(MemoryUtil.memAddress(emi), totalVerts * 4);
            wEmiMem = upload(emiB, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, pb, stack);
            wEmiBuf = pb.get(0);
        }
        MemoryUtil.memFree(pos);
        MemoryUtil.memFree(col);
        MemoryUtil.memFree(emi);
    }

    public void uploadLights(java.util.List<game.world.MapFixes.PLight> lights) {
        allLights = java.util.List.copyOf(lights);
        uploadedLightCount = 0;
        System.out.println("Point lights registered: " + lights.size());
    }

    private void refreshLights(Vector3f eye, MemoryStack stack) {
        int total = Math.min(allLights.size(), MAX_LIGHTS);
        if (total == 0) {
            uploadedLightCount = 0;
            return;
        }
        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> {
            game.world.MapFixes.PLight la = allLights.get(a);
            game.world.MapFixes.PLight lb = allLights.get(b);
            float da = (la.x - eye.x) * (la.x - eye.x)
                    + (la.y - eye.y) * (la.y - eye.y)
                    + (la.z - eye.z) * (la.z - eye.z);
            float db = (lb.x - eye.x) * (lb.x - eye.x)
                    + (lb.y - eye.y) * (lb.y - eye.y)
                    + (lb.z - eye.z) * (lb.z - eye.z);
            return Float.compare(da, db);
        });
        try (MemoryStack inner = MemoryStack.stackPush()) {
            PointerBuffer mp = inner.mallocPointer(1);
            check(vkMapMemory(device, lightMem, 0, MAX_LIGHTS * 2 * 16, 0, mp), "vkMapMemory(lights)");
            FloatBuffer fb = mp.getFloatBuffer(0, MAX_LIGHTS * 8);
            int n = Math.min(total, ACTIVE_LIGHTS);
            for (int i = 0; i < n; i++) {
                game.world.MapFixes.PLight l = allLights.get(order[i]);
                int o = i * 8;
                fb.put(o + 0, l.x); fb.put(o + 1, l.y); fb.put(o + 2, l.z); fb.put(o + 3, l.radius);
                fb.put(o + 4, l.r); fb.put(o + 5, l.g); fb.put(o + 6, l.b); fb.put(o + 7, 1f);
            }
            vkUnmapMemory(device, lightMem);
            uploadedLightCount = n;
        }
    }

    private void putPart(FloatBuffer pos, FloatBuffer col, IntBuffer emi,
                         ObjLoader.MeshPart p, boolean emissive) {
        float[] pp = p.positions;
        float[] cc = p.colors;
        int verts = pp.length / 3;
        pos.put(pp);
        col.put(cc);
        for (int i = 0; i < verts; i++) {
            emi.put(emissive ? 1 : 0);
        }
    }

    public void uploadFontAtlas(FontAtlas atlas) {
        ByteBuffer pixels = atlas.pixels();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] img = new long[1];
            fontMem = createImage(atlas.width, atlas.height, VK_FORMAT_R8_UNORM,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, img, stack);
            fontImage = img[0];
            fontView = createImageView(fontImage, VK_FORMAT_R8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, stack);

            long staging = ensureStaging(pixels.remaining(), stack);
            PointerBuffer mapPb = stack.mallocPointer(1);
            check(vkMapMemory(device, stagingMem, 0, stagingSize, 0, mapPb), "vkMapMemory");
            ByteBuffer mapped = mapPb.getByteBuffer(0, (int) stagingSize);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), MemoryUtil.memAddress(mapped),
                    pixels.remaining());
            vkUnmapMemory(device, stagingMem);

            submitOneShotRecorder(cmd -> {
                transitionImage(cmd, fontImage, VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0)
                        .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .imageOffset(o -> o.set(0, 0, 0))
                        .imageExtent(e -> e.width(atlas.width).height(atlas.height).depth(1));
                vkCmdCopyBufferToImage(cmd, staging, fontImage,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                transitionImage(cmd, fontImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);
            }, stack);

            VkDescriptorImageInfo.Buffer dii = VkDescriptorImageInfo.calloc(1, stack);
            dii.get(0).sampler(sampler).imageView(fontView)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer wds = VkWriteDescriptorSet.calloc(1, stack);
            wds.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descSet)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(dii);
            vkUpdateDescriptorSets(device, wds, null);
        }
    }

    private static void transitionImage(VkCommandBuffer cmd, long image, int oldLayout, int newLayout,
                                        int aspect) {
        int srcStage, dstStage, srcAccess, dstAccess;
        if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            srcAccess = 0;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            dstAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
        } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            srcAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            dstAccess = VK_ACCESS_SHADER_READ_BIT;
        } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            srcAccess = 0;
            dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dstAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        } else if (oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                && newLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
            srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            srcAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            dstStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            dstAccess = 0;
        } else if (oldLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
                && newLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
            srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            srcAccess = 0;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            dstAccess = VK_ACCESS_TRANSFER_READ_BIT;
        } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                && newLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            srcAccess = VK_ACCESS_TRANSFER_READ_BIT;
            dstStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            dstAccess = 0;
        } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                && newLayout == VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL) {
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            srcAccess = 0;
            dstStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            dstAccess = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        } else {
            throw new IllegalArgumentException("Unsupported layout transition " + oldLayout + " -> " + newLayout);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .subresourceRange(r -> r.aspectMask(aspect)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1))
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    private static final float SUN_X = 0.35f, SUN_Y = 0.85f, SUN_Z = -0.40f;

    private static void putIdentityModel(FloatBuffer pf, int at) {
        pf.put(at, 1f);      pf.put(at + 1, 0f);  pf.put(at + 2, 0f);  pf.put(at + 3, 0f);
        pf.put(at + 4, 0f);  pf.put(at + 5, 1f);  pf.put(at + 6, 0f);  pf.put(at + 7, 0f);
        pf.put(at + 8, 0f);  pf.put(at + 9, 0f);  pf.put(at + 10, 1f); pf.put(at + 11, 0f);
        pf.put(at + 12, 0f); pf.put(at + 13, 0f); pf.put(at + 14, 0f); pf.put(at + 15, 1f);
    }

    private static final float[][] CUBE_FACES = {

            { 1,-1,-1,  1, 1,-1,  1, 1, 1,   1,-1,-1,  1, 1, 1,  1,-1, 1},

            {-1,-1,-1, -1,-1, 1, -1, 1, 1,  -1,-1,-1, -1, 1, 1, -1, 1,-1},

            {-1, 1,-1, -1, 1, 1,  1, 1, 1,  -1, 1,-1,  1, 1, 1,  1, 1,-1},

            {-1,-1,-1,  1,-1,-1,  1,-1, 1,  -1,-1,-1,  1,-1, 1, -1,-1, 1},

            {-1,-1, 1,  1,-1, 1,  1, 1, 1,  -1,-1, 1,  1, 1, 1, -1, 1, 1},

            {-1,-1,-1, -1, 1,-1,  1, 1,-1,  -1,-1,-1,  1, 1,-1,  1,-1,-1},
    };

    private static final int DYN_MAX_VERTS = 8000;
    private static final int PART_VERTS = 36;

    private static final class DynPart {
        final org.joml.Matrix4f m = new org.joml.Matrix4f();
        float hx, hy, hz, r, g, b, a = 1f;
        int cylSides;
        boolean shadow;
        int verts = PART_VERTS;

        float ao = 1f;
    }

    private final java.util.ArrayList<DynPart> dynParts = new java.util.ArrayList<>(160);
    private final java.util.ArrayList<DynPart> dynPool = new java.util.ArrayList<>(160);

    private int npcShadowFirst, npcShadowCount;

    private int npcOpaqueVerts;

    private DynPart obtainPart() {
        if (dynPool.size() > dynParts.size()) {
            return dynPool.get(dynParts.size());
        }
        DynPart d = new DynPart();
        dynPool.add(d);
        return d;
    }

    private boolean takeBudget(int have, int need) {
        return have + need <= DYN_MAX_VERTS;
    }

    private int buildNpcMesh(java.util.List<game.world.Npc> npcs, java.nio.ByteBuffer out) {
        out.clear();
        java.nio.FloatBuffer posF = out.asFloatBuffer();

        dynParts.clear();
        int used = 0;
        for (game.world.Npc npc : npcs) {
            for (int pi = 0; pi < npc.parts().size(); pi++) {
                game.world.Npc.Part part = npc.parts().get(pi);
                int need = part.vertCount();
                if (!takeBudget(used, need)) {
                    break;
                }
                DynPart d = obtainPart();
                npc.partMatrix(pi, 0f, d.m);
                d.hx = part.hx; d.hy = part.hy; d.hz = part.hz;
                d.r = part.r; d.g = part.g; d.b = part.b; d.a = 1f;
                d.cylSides = part.cylSides;
                d.shadow = false;
                d.verts = need;
                d.ao = AmbientOcclusion.verticalFactor(d.m.m31(), npc.groundY);
                dynParts.add(d);
                used += need;
            }
        }
        if (beds != null) {
            for (game.world.MovableBed bed : beds) {
                for (int pi = 0; pi < bed.parts().size(); pi++) {
                    if (!takeBudget(used, PART_VERTS)) {
                        break;
                    }
                    game.world.MovableBed.Part part = bed.parts().get(pi);
                    DynPart d = obtainPart();
                    bed.partMatrix(pi, d.m);
                    d.hx = part.hx; d.hy = part.hy; d.hz = part.hz;
                    d.r = part.r; d.g = part.g; d.b = part.b; d.a = 1f;
                    d.cylSides = 0;
                    d.shadow = false;
                    d.verts = PART_VERTS;
                    d.ao = AmbientOcclusion.verticalFactor(d.m.m31(), bed.groundY);
                    dynParts.add(d);
                    used += PART_VERTS;
                }
            }
        }
        npcOpaqueVerts = used;
        npcShadowFirst = used;

        for (game.world.Npc npc : npcs) {
            if (npc.lying || Math.abs(npc.groundY - npc.y) > 6f) {
                continue;
            }
            if (!takeBudget(used, 6)) {
                break;
            }
            DynPart d = obtainPart();
            d.m.identity();
            d.m.translate(npc.x, npc.groundY + 0.12f, npc.z);
            d.hx = 2.6f; d.hy = 0f; d.hz = 2.6f;
            d.r = 0f; d.g = 0f; d.b = 0f; d.a = 0.34f;
            d.cylSides = 0;
            d.shadow = true;
            d.verts = 6;
            dynParts.add(d);
            used += 6;
        }
        if (beds != null) {
            for (game.world.MovableBed bed : beds) {
                if (Math.abs(bed.groundY - bed.y) > 6f) {
                    continue;
                }
                if (!takeBudget(used, 6)) {
                    break;
                }
                DynPart d = obtainPart();
                d.m.identity();
                d.m.translate(bed.x, bed.groundY + 0.12f, bed.z);
                d.hx = game.world.MovableBed.HALF_W + 1.2f;
                d.hy = 0f;
                d.hz = game.world.MovableBed.HALF_L + 1.2f;
                d.r = 0f; d.g = 0f; d.b = 0f; d.a = 0.30f;
                d.cylSides = 0;
                d.shadow = true;
                d.verts = 6;
                dynParts.add(d);
                used += 6;
            }
        }
        npcShadowCount = used - npcShadowFirst;

        org.joml.Vector3f tmp = new org.joml.Vector3f();
        int posFloats = 0;
        for (DynPart d : dynParts) {
            if (d.shadow) {
                emitQuad(posF, tmp, d, posFloats);
                posFloats += 18;
            } else if (d.cylSides > 0) {
                posFloats = emitCyl(posF, tmp, d, posFloats);
            } else {
                for (float[] face : CUBE_FACES) {
                    for (int i = 0; i < face.length; i += 3) {
                        tmp.set(face[i] * d.hx, face[i + 1] * d.hy, face[i + 2] * d.hz);
                        d.m.transformPosition(tmp);
                        posF.put(posFloats, tmp.x);
                        posF.put(posFloats + 1, tmp.y);
                        posF.put(posFloats + 2, tmp.z);
                        posFloats += 3;
                    }
                }
            }
        }
        int vertCount = posFloats / 3;

        int cf = posFloats;
        for (DynPart d : dynParts) {
            int n = d.shadow ? 6 : d.verts;
            float ar = d.r * d.ao, ag = d.g * d.ao, ab = d.b * d.ao;
            for (int i = 0; i < n; i++) {
                posF.put(cf++, ar);
                posF.put(cf++, ag);
                posF.put(cf++, ab);
                posF.put(cf++, d.a);
            }
        }

        java.nio.IntBuffer emiI = out.asIntBuffer();
        int intBase = posFloats + vertCount * 4;
        for (int i = 0; i < vertCount; i++) {
            emiI.put(intBase + i, 0);
        }
        int totalBytes = (posFloats + vertCount * 4 + vertCount) * 4;
        out.position(totalBytes);
        return vertCount;
    }

    private static void emitQuad(java.nio.FloatBuffer posF, org.joml.Vector3f tmp,
                                 DynPart d, int base) {
        float[][] q = {
                {-d.hx, 0f, -d.hz}, {d.hx, 0f, -d.hz}, {d.hx, 0f, d.hz},
                {-d.hx, 0f, -d.hz}, {d.hx, 0f, d.hz}, {-d.hx, 0f, d.hz},
        };
        for (int i = 0; i < 6; i++) {
            tmp.set(q[i][0], q[i][1], q[i][2]);
            d.m.transformPosition(tmp);
            posF.put(base + i * 3, tmp.x);
            posF.put(base + i * 3 + 1, tmp.y);
            posF.put(base + i * 3 + 2, tmp.z);
        }
    }

    private static int emitCyl(java.nio.FloatBuffer posF, org.joml.Vector3f tmp,
                               DynPart d, int base) {
        int n = d.cylSides;
        int o = base;
        for (int i = 0; i < n; i++) {
            double a0 = 2.0 * Math.PI * i / n;
            double a1 = 2.0 * Math.PI * (i + 1) / n;
            float x0 = (float) (Math.cos(a0) * d.hx), z0 = (float) (Math.sin(a0) * d.hz);
            float x1 = (float) (Math.cos(a1) * d.hx), z1 = (float) (Math.sin(a1) * d.hz);
            float[][] side = {
                    {x0, -d.hy, z0}, {x1, -d.hy, z1}, {x1, d.hy, z1},
                    {x0, -d.hy, z0}, {x1, d.hy, z1}, {x0, d.hy, z0},
            };
            for (float[] v : side) {
                tmp.set(v[0], v[1], v[2]);
                d.m.transformPosition(tmp);
                posF.put(o++, tmp.x);
                posF.put(o++, tmp.y);
                posF.put(o++, tmp.z);
            }
            float[][] cap = {
                    {0f, d.hy, 0f}, {x1, d.hy, z1}, {x0, d.hy, z0},
                    {0f, -d.hy, 0f}, {x0, -d.hy, z0}, {x1, -d.hy, z1},
            };
            for (float[] v : cap) {
                tmp.set(v[0], v[1], v[2]);
                d.m.transformPosition(tmp);
                posF.put(o++, tmp.x);
                posF.put(o++, tmp.y);
                posF.put(o++, tmp.z);
            }
        }
        return o;
    }

    public boolean renderFrame(Player player, ByteBuffer hudMesh, int hudVertexCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (framebufferResized) {
                framebufferResized = false;
                recreateSwapchain(stack);
            }

            int f = frameIdx;
            vkWaitForFences(device, fences[f], true, 0xFFFFFFFFFFFFFFFFL);

            int err = vkAcquireNextImageKHR(device, swapchain, 0xFFFFFFFFFFFFFFFFL,
                    imageAvail[f], VK_NULL_HANDLE, acquiredIdx);
            if (err == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain(stack);
                return false;
            } else if (err != VK_SUCCESS && err != VK_SUBOPTIMAL_KHR) {
                check(err, "vkAcquireNextImageKHR");
            }
            int image = acquiredIdx.get(0);
            lastImageIdx = image;

            vkResetFences(device, fences[f]);
            VkCommandBuffer cmd = cmds[f];
            vkResetCommandBuffer(cmd, 0);
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            vkBeginCommandBuffer(cmd, bi);

            transitionImage(cmd, swapImages[image], VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);

            if (hudMesh != null && hudMesh.hasRemaining()) {
                long hudOffset = 0;
                while (hudMesh.hasRemaining()) {
                    int chunk = Math.min(hudMesh.remaining(), 65536);
                    ByteBuffer slice = hudMesh.slice();
                    slice.limit(chunk);
                    vkCmdUpdateBuffer(cmd, hudBuf, hudOffset, slice);
                    hudMesh.position(hudMesh.position() + chunk);
                    hudOffset += chunk;
                }
            }

            frameCount++;
            int curNpcVerts = npcVertexCount;
            boolean dirty = dynamicDirty || (frameCount % 30 == 0);
            dynamicDirty = false;
            int listSize = npcList != null ? npcList.size() : 0;
            if (listSize != lastDynCount) {
                dirty = true;
                lastDynCount = listSize;
            }
            if (!dirty && npcList != null) {
                for (game.world.Npc n : npcList) {
                    if (n.isMoving()) {
                        dirty = true;
                        break;
                    }
                }
            }
            if (dirty && npcList != null && !npcList.isEmpty()) {
                curNpcVerts = buildNpcMesh(npcList, npcScratch);
                if (curNpcVerts > 0) {
                    npcScratch.flip();

                    long updateOff = 0;
                    while (npcScratch.hasRemaining()) {
                        int chunk = Math.min(npcScratch.remaining(), 65536);
                        ByteBuffer slice = npcScratch.slice();
                        slice.limit(chunk);
                        vkCmdUpdateBuffer(cmd, npcBuf, updateOff, slice);
                        npcScratch.position(npcScratch.position() + chunk);
                        updateOff += chunk;
                    }
                }
                npcVertexCount = curNpcVerts;
            }

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).x(0).y(swH).width(swW).height(-swH).minDepth(0).maxDepth(1);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset(o -> o.set(0, 0)).extent(e -> e.set(swW, swH));
            vkCmdSetViewport(cmd, 0, viewport);
            vkCmdSetScissor(cmd, 0, scissor);

            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
            color.get(0)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(swapViews[image])
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(c -> c.color(cc -> cc.float32(stack.floats(0.10f, 0.11f, 0.14f, 1.0f))));

            VkRenderingAttachmentInfo depthAtt = VkRenderingAttachmentInfo.calloc(stack);
            depthAtt
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(depthView)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .clearValue(c -> c.color(cc -> cc.float32(stack.floats(1.0f, 0f, 0f, 0f))));

            VkRenderingInfo ri = VkRenderingInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                    .renderArea(a -> a.offset(o -> o.set(0, 0)).extent(e -> e.set(swW, swH)))
                    .layerCount(1)
                    .pColorAttachments(color)
                    .pDepthAttachment(depthAtt);

            VkRenderingAttachmentInfo.Buffer primerColor = VkRenderingAttachmentInfo.calloc(1, stack);
            primerColor.get(0)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(swapViews[image])
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(c -> c.color(cc -> cc.float32(stack.floats(1.0f, 0.0f, 0.0f, 1.0f))));
            VkRenderingInfo primerRi = VkRenderingInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                    .renderArea(a -> a.offset(o -> o.set(0, 0)).extent(e -> e.set(swW, swH)))
                    .layerCount(1)
                    .pColorAttachments(primerColor);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, primerRi);
            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, ri);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, mapPipeline);

            ByteBuffer pcb = stack.malloc(160);
            FloatBuffer pf = pcb.asFloatBuffer();
            pf.position(0);
            player.writeViewProj(pf, (float) swW / (float) swH);
            pf.position(16);
            float sunLen = (float) Math.sqrt(SUN_X * SUN_X + SUN_Y * SUN_Y + SUN_Z * SUN_Z);
            pf.put(SUN_X / sunLen).put(SUN_Y / sunLen).put(SUN_Z / sunLen)
              .put(uploadedLightCount);
            Vector3f eye = player.eyePosition(new Vector3f());
            pf.put(eye.x).put(eye.y).put(eye.z).put(1f);
            putIdentityModel(pf, 24);
            vkCmdPushConstants(cmd, mapLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcb);

            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, mapLayout, 0,
                    stack.longs(lightSet), null);
            vkCmdBindVertexBuffers(cmd, 0,
                    stack.longs(wPosBuf, wColBuf, wEmiBuf),
                    stack.longs(0, 0, 0));

            if (mapOpaqueCount > 0) {
                vkCmdDraw(cmd, mapOpaqueCount, 1, 0, 0);
            }

            if (npcOpaqueVerts > 0) {
                int posBytes = curNpcVerts * 12;
                int colBytes = curNpcVerts * 16;
                vkCmdBindVertexBuffers(cmd, 0,
                        stack.longs(npcBuf, npcBuf, npcBuf),
                        stack.longs(0, posBytes, posBytes + colBytes));
                vkCmdDraw(cmd, npcOpaqueVerts, 1, 0, 0);
                vkCmdBindVertexBuffers(cmd, 0,
                        stack.longs(wPosBuf, wColBuf, wEmiBuf),
                        stack.longs(0, 0, 0));
            }

            Matrix4f model = new Matrix4f();
            FloatBuffer modelF = stack.mallocFloat(16);
            for (DoorSeg seg : doorSegs) {
                if (seg.transparent || seg.count == 0) {
                    continue;
                }
                doorLeaves.get(seg.leaf).model(doorLeaves.get(seg.leaf).open, model);
                modelF.clear();
                model.get(modelF);
                for (int i = 0; i < 16; i++) {
                    pf.put(24 + i, modelF.get(i));
                }
                vkCmdPushConstants(cmd, mapLayout,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcb);
                vkCmdDraw(cmd, seg.count, 1, seg.first, 0);
            }

            if (frameCount % 15 == 0) {
                refreshLights(eye, stack);
            }

            if (mapTransCount > 0 || doorSegs.stream().anyMatch(s -> s.transparent)
                    || npcShadowCount > 0) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, glassPipeline);
                Vector3f cam = player.eyePosition(new Vector3f());
                if (npcShadowCount > 0 && curNpcVerts > 0) {

                    int posBytes = curNpcVerts * 12;
                    int colBytes = curNpcVerts * 16;
                    putIdentityModel(pf, 24);
                    vkCmdPushConstants(cmd, mapLayout,
                            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcb);
                    vkCmdBindVertexBuffers(cmd, 0,
                            stack.longs(npcBuf, npcBuf, npcBuf),
                            stack.longs(0, posBytes, posBytes + colBytes));
                    vkCmdDraw(cmd, npcShadowCount, 1, npcShadowFirst, 0);
                    vkCmdBindVertexBuffers(cmd, 0,
                            stack.longs(wPosBuf, wColBuf, wEmiBuf),
                            stack.longs(0, 0, 0));
                }
                List<DoorSeg> ts = new ArrayList<>();
                if (mapTransCount > 0) {
                    DoorSeg mapSeg = new DoorSeg();
                    mapSeg.leaf = -1;
                    mapSeg.first = mapTransFirst;
                    mapSeg.count = mapTransCount;
                    mapSeg.transparent = true;
                    mapSeg.cx = 0f;
                    mapSeg.cy = 0f;
                    mapSeg.cz = 0f;
                    ts.add(mapSeg);
                }
                for (DoorSeg seg : doorSegs) {
                    if (seg.transparent && seg.count > 0) {
                        ts.add(seg);
                    }
                }
                Vector3f tmp = new Vector3f();
                ts.sort((a, b) -> {
                    float da = segDist2(a, cam, tmp, model);
                    float db = segDist2(b, cam, tmp, model);
                    return Float.compare(db, da);
                });
                for (DoorSeg seg : ts) {
                    if (seg.leaf >= 0) {
                        Doors.Leaf leaf = doorLeaves.get(seg.leaf);
                        leaf.model(leaf.open, model);
                        modelF.clear();
                        model.get(modelF);
                        for (int i = 0; i < 16; i++) {
                            pf.put(24 + i, modelF.get(i));
                        }
                    } else {
                        putIdentityModel(pf, 24);
                    }
                    vkCmdPushConstants(cmd, mapLayout,
                            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcb);
                    vkCmdDraw(cmd, seg.count, 1, seg.first, 0);
                }
            }

            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);

            color.get(0).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
            ri.pDepthAttachment(null);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, ri);

            if (hudVertexCount > 0) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, hudPipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, hudLayout, 0,
                        stack.longs(descSet), null);
                ByteBuffer hudPc = stack.malloc(8);
                hudPc.putFloat(0, swW).putFloat(4, swH);
                vkCmdPushConstants(cmd, hudLayout,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, hudPc);

                int posBytes = hudVertexCount * 8;
                int uvBytes = hudVertexCount * 8;
                vkCmdBindVertexBuffers(cmd, 0,
                        stack.longs(hudBuf, hudBuf, hudBuf),
                        stack.longs(0, posBytes, posBytes + uvBytes));
                vkCmdDraw(cmd, hudVertexCount, 1, 0, 0);
            }

            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);

            transitionImage(cmd, swapImages[image], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_ASPECT_COLOR_BIT);
            vkEndCommandBuffer(cmd);

            VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(imageAvail[f]))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(renderDone[f]));
            check(vkQueueSubmit(queue, si, fences[f]), "submit");

            VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderDone[f]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(acquiredIdx);
            int perr = vkQueuePresentKHR(queue, pi);
            if (perr == VK_ERROR_OUT_OF_DATE_KHR) {
                framebufferResized = true;
            } else if (perr != VK_SUCCESS && perr != VK_SUBOPTIMAL_KHR) {
                check(perr, "vkQueuePresentKHR");
            }

            frameIdx = (frameIdx + 1) % MAX_FRAMES;
            return true;
        }
    }

    private float segDist2(DoorSeg seg, Vector3f cam, Vector3f tmp, Matrix4f model) {
        if (seg.leaf >= 0) {
            Doors.Leaf leaf = doorLeaves.get(seg.leaf);
            leaf.model(leaf.open, model);
            tmp.set(seg.cx, seg.cy, seg.cz);
            model.transformPosition(tmp);
        } else {
            tmp.set(seg.cx, seg.cy, seg.cz);
        }
        float dx = tmp.x - cam.x, dy = tmp.y - cam.y, dz = tmp.z - cam.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public byte[] captureFrame() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkQueueWaitIdle(queue);
            long img = swapImages[lastImageIdx];
            int size = swW * swH * 4;

            PointerBuffer pb = stack.mallocPointer(1);
            long mem = createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pb, stack);
            long buf = pb.get(0);

            VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(cmdPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer cpb = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, cai, cpb), "capture alloc");
            VkCommandBuffer cmd = new VkCommandBuffer(cpb.get(0), device);

            vkResetCommandBuffer(cmd, 0);
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            vkBeginCommandBuffer(cmd, bi);
            transitionImage(cmd, img, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .imageSubresource(s -> s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .imageOffset(o -> o.set(0, 0, 0))
                    .imageExtent(e -> e.width(swW).height(swH).depth(1));
            vkCmdCopyImageToBuffer(cmd, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buf, region);
            transitionImage(cmd, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_ASPECT_COLOR_BIT);
            vkEndCommandBuffer(cmd);

            long fence = createFence(stack);
            vkResetFences(device, fence);
            VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(queue, si, fence), "capture submit");
            vkWaitForFences(device, fence, true, 0xFFFFFFFFFFFFFFFFL);
            vkDestroyFence(device, fence, null);

            PointerBuffer mapPb = stack.mallocPointer(1);
            check(vkMapMemory(device, mem, 0, size, 0, mapPb), "capture map");
            ByteBuffer mapped = mapPb.getByteBuffer(0, size);
            byte[] out = new byte[size];
            mapped.get(out);
            vkUnmapMemory(device, mem);

            vkDestroyBuffer(device, buf, null);
            vkFreeMemory(device, mem, null);
            return out;
        }
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(device);
        for (long v : swapViews) {
            vkDestroyImageView(device, v, null);
        }
        vkDestroyImageView(device, depthView, null);
        vkDestroyImage(device, depthImage, null);
        vkFreeMemory(device, depthMem, null);
        destroyMsaaColor();
        vkDestroySwapchainKHR(device, swapchain, null);

        if (wPosBuf != 0) vkDestroyBuffer(device, wPosBuf, null);
        if (wPosMem != 0) vkFreeMemory(device, wPosMem, null);
        if (wColBuf != 0) vkDestroyBuffer(device, wColBuf, null);
        if (wColMem != 0) vkFreeMemory(device, wColMem, null);
        if (wEmiBuf != 0) vkDestroyBuffer(device, wEmiBuf, null);
        if (wEmiMem != 0) vkFreeMemory(device, wEmiMem, null);
        if (hudBuf != 0) vkDestroyBuffer(device, hudBuf, null);
        if (hudMem != 0) vkFreeMemory(device, hudMem, null);
        if (npcBuf != 0) vkDestroyBuffer(device, npcBuf, null);
        if (npcMem != 0) vkFreeMemory(device, npcMem, null);
        MemoryUtil.memFree(npcScratch);
        if (stagingBuf != 0) vkDestroyBuffer(device, stagingBuf, null);
        if (stagingMem != 0) vkFreeMemory(device, stagingMem, null);

        if (fontView != 0) vkDestroyImageView(device, fontView, null);
        if (fontImage != 0) vkDestroyImage(device, fontImage, null);
        if (fontMem != 0) vkFreeMemory(device, fontMem, null);
        if (sampler != 0) vkDestroySampler(device, sampler, null);
        if (descPool != 0) vkDestroyDescriptorPool(device, descPool, null);
        if (descLayout != 0) vkDestroyDescriptorSetLayout(device, descLayout, null);

        if (lightBuf != 0) vkDestroyBuffer(device, lightBuf, null);
        if (lightMem != 0) vkFreeMemory(device, lightMem, null);
        if (lightPool != 0) vkDestroyDescriptorPool(device, lightPool, null);
        if (lightLayout != 0) vkDestroyDescriptorSetLayout(device, lightLayout, null);
        if (mapPipeline != 0) vkDestroyPipeline(device, mapPipeline, null);
        if (glassPipeline != 0) vkDestroyPipeline(device, glassPipeline, null);
        if (mapLayout != 0) vkDestroyPipelineLayout(device, mapLayout, null);
        if (hudPipeline != 0) vkDestroyPipeline(device, hudPipeline, null);
        if (hudLayout != 0) vkDestroyPipelineLayout(device, hudLayout, null);

        for (int i = 0; i < MAX_FRAMES; i++) {
            vkDestroyFence(device, fences[i], null);
            vkDestroySemaphore(device, imageAvail[i], null);
            vkDestroySemaphore(device, renderDone[i], null);
        }
        vkDestroyCommandPool(device, cmdPool, null);
        if (memProps != null) memProps.free();

        vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
        MemoryUtil.memFree(acquiredIdx);
    }

    private static void check(int err, String what) {
        if (err != VK_SUCCESS) {
            throw new RuntimeException(what + " failed: Vulkan error " + err);
        }
    }
}
