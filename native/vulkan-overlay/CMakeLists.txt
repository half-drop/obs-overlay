cmake_minimum_required(VERSION 3.21)
cmake_policy(SET CMP0091 NEW)
project(obs_overlay_vulkan VERSION 1.0.0 LANGUAGES CXX)

set(VULKAN_HEADERS_DIR "" CACHE PATH "Pinned Vulkan-Headers include directory")
set(GLSLANG_VALIDATOR "" CACHE FILEPATH "Pinned glslang or glslangValidator executable")
if(NOT EXISTS "${VULKAN_HEADERS_DIR}/vulkan/vk_layer.h")
    message(FATAL_ERROR "Set VULKAN_HEADERS_DIR to a Vulkan-Headers include directory")
endif()
if(NOT EXISTS "${GLSLANG_VALIDATOR}")
    message(FATAL_ERROR "Set GLSLANG_VALIDATOR to the glslang executable")
endif()

set(shader_dir "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${shader_dir}")
foreach(stage IN ITEMS vert frag)
    add_custom_command(OUTPUT "${shader_dir}/hud.${stage}.spv"
        COMMAND "${GLSLANG_VALIDATOR}" -V --target-env vulkan1.0
            -S "${stage}" "${CMAKE_CURRENT_SOURCE_DIR}/shaders/hud.${stage}"
            -o "${shader_dir}/hud.${stage}.spv"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/hud.${stage}"
        VERBATIM)
endforeach()
add_custom_command(OUTPUT "${shader_dir}/hud_shaders.hpp"
    COMMAND "${CMAKE_COMMAND}" "-DSHADER_DIR=${shader_dir}"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedShaders.cmake"
    DEPENDS "${shader_dir}/hud.vert.spv" "${shader_dir}/hud.frag.spv"
        "${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedShaders.cmake"
    VERBATIM)

add_library(obs_overlay_vulkan SHARED src/layer.cpp "${shader_dir}/hud_shaders.hpp")
target_compile_features(obs_overlay_vulkan PRIVATE cxx_std_17)
target_compile_definitions(obs_overlay_vulkan PRIVATE OBSVK_BUILD VK_NO_PROTOTYPES)
target_include_directories(obs_overlay_vulkan PRIVATE
    "${VULKAN_HEADERS_DIR}" include "${shader_dir}")
set_target_properties(obs_overlay_vulkan PROPERTIES
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
if(MSVC)
    target_compile_options(obs_overlay_vulkan PRIVATE /W4 /EHsc /permissive-)
else()
    target_compile_options(obs_overlay_vulkan PRIVATE -Wall -Wextra -Wpedantic -Wno-missing-field-initializers)
    target_link_options(obs_overlay_vulkan PRIVATE -Wl,-Bsymbolic -Wl,--no-undefined)
endif()

if(WIN32)
    set(OBSVK_LIBRARY_PATH ".\\\\obs_overlay_vulkan.dll")
else()
    set(OBSVK_LIBRARY_PATH "./libobs_overlay_vulkan.so")
endif()
configure_file(layer.json.in obs_overlay_vulkan.json @ONLY)
install(TARGETS obs_overlay_vulkan RUNTIME DESTINATION . LIBRARY DESTINATION .)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/obs_overlay_vulkan.json" DESTINATION .)
