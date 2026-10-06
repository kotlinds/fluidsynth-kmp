cmake_minimum_required(VERSION 3.22)
project(fluidsynth_jni)

add_library(fluidsynth_jni SHARED fluidsynth_jni.c)
target_include_directories(fluidsynth_jni PRIVATE ${CMAKE_SOURCE_DIR}/../../../../libs/include)

add_library(fluidsynth SHARED IMPORTED)
set_target_properties(fluidsynth PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libfluidsynth.so)

find_library(log-lib log)

target_link_libraries(fluidsynth_jni fluidsynth ${log-lib})
target_link_options(fluidsynth_jni PRIVATE "-Wl,-z,max-page-size=16384")
