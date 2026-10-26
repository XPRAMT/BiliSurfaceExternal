cmake_minimum_required(VERSION 3.22.1)
project(bilisurfacebridge)

add_library(bilisurfacebridge SHARED native_refresh_cap.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(bilisurfacebridge ${log-lib} ${android-lib})
