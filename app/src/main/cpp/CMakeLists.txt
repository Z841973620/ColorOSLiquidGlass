cmake_minimum_required(VERSION 3.22.1)
project(colg_shm)

# Futex wake/wait on SharedMemory mmap words only (Ashmem path; no AHardwareBuffer).
add_library(colg_shm SHARED colg_futex.cpp)
find_library(log-lib log)
target_link_libraries(colg_shm ${log-lib})
target_compile_options(colg_shm PRIVATE -Wall -O3)
