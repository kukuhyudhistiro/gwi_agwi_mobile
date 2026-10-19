cmake_minimum_required(VERSION 3.22.1)
project("gwi_odps_native")

# Use Prefab-enabled OpenCV from Maven
find_package(OpenCV REQUIRED CONFIG)

add_library(gwi_odps_native SHARED gwi_native.cpp)

# Link against the OpenCV target provided by Prefab
target_link_libraries(gwi_odps_native
    OpenCV::opencv_java4
    log
)

target_link_options(gwi_odps_native PRIVATE "-Wl,-z,max-page-size=16384")

