cmake_minimum_required(VERSION 3.22.1)
project(ironmon_jni CXX C)

include(FetchContent)

# ── Lua 5.4 ──────────────────────────────────────────────────────────────────
# We build Lua from source so mGBA's find_feature(USE_LUA "Lua") can find it.
FetchContent_Declare(lua_src
    URL      https://www.lua.org/ftp/lua-5.4.7.tar.gz
    URL_HASH SHA256=9fbf5e28ef86c69858f6d3d34eccc32e911c1a28b4120ff3e84aaa70cfbf1e30
)
FetchContent_MakeAvailable(lua_src)

# Build Lua as a static library from individual source files.
file(GLOB LUA_SOURCES ${lua_src_SOURCE_DIR}/src/*.c)
list(FILTER LUA_SOURCES EXCLUDE REGEX ".*(lua|luac)\\.c$")
add_library(lua54 STATIC ${LUA_SOURCES})
target_compile_definitions(lua54 PRIVATE
    LUA_USE_POSIX       # Android is POSIX
)
target_include_directories(lua54 PUBLIC ${lua_src_SOURCE_DIR}/src)
set_target_properties(lua54 PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Pre-set variables so mGBA's find_feature(USE_LUA "Lua") skips find_package
set(Lua_FOUND        TRUE  CACHE BOOL   "" FORCE)
set(LUA_FOUND        TRUE  CACHE BOOL   "" FORCE)
# mGBA uses LUA_LIBRARY (singular) in DEPENDENCY_LIB — must set both forms
set(Lua_LIBRARY      lua54 CACHE STRING "" FORCE)
set(LUA_LIBRARY      lua54 CACHE STRING "" FORCE)
set(Lua_LIBRARIES    lua54 CACHE STRING "" FORCE)
set(LUA_LIBRARIES    lua54 CACHE STRING "" FORCE)
set(LUA_INCLUDE_DIR  ${lua_src_SOURCE_DIR}/src CACHE STRING "" FORCE)
set(LUA_INCLUDE_DIRS ${lua_src_SOURCE_DIR}/src CACHE STRING "" FORCE)
set(LUA_VERSION_STRING "5.4.7" CACHE STRING "" FORCE)
set(LUA_VERSION_MAJOR 5 CACHE STRING "" FORCE)
set(LUA_VERSION_MINOR 4 CACHE STRING "" FORCE)
set(Lua_VERSION      "5.4.7" CACHE STRING "" FORCE)

# ── mGBA 0.10.5 ───────────────────────────────────────────────────────────────
FetchContent_Declare(mgba_src
    GIT_REPOSITORY https://github.com/mgba-emu/mgba.git
    GIT_TAG        0.10.5
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(mgba_src)

# ── JNI bridge ────────────────────────────────────────────────────────────────
add_library(ironmon_jni SHARED mgba_jni.cpp)

target_include_directories(ironmon_jni PRIVATE
    ${mgba_src_SOURCE_DIR}/include
    ${lua_src_SOURCE_DIR}/src
)

target_link_libraries(ironmon_jni
    mgba
    lua54
    android
    log
)
