# Same as the SDK arm64-android-mega triplet, but release-only and for API 35 (the app minSdk).
set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE static)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_BUILD_TYPE release)
set(VCPKG_CMAKE_SYSTEM_NAME Android)
set(VCPKG_CMAKE_SYSTEM_VERSION 35)
set(VCPKG_MAKE_BUILD_TRIPLET "--host=aarch64-linux-android")
set(VCPKG_CMAKE_CONFIGURE_OPTIONS -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-${VCPKG_CMAKE_SYSTEM_VERSION} -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON)

# Keep the checkout path out of __FILE__ strings. OpenSSL compiles with relative paths and records
# its compiler flags in the library, so it does not get the map.
if(NOT PORT STREQUAL "openssl")
    get_filename_component(MEGAGLASS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
    set(VCPKG_C_FLAGS "-ffile-prefix-map=${MEGAGLASS_ROOT}/=")
    set(VCPKG_CXX_FLAGS "${VCPKG_C_FLAGS}")
endif()
