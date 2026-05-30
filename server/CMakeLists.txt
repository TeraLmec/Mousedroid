cmake_minimum_required(VERSION 3.26)
project(Mousedroid)

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED TRUE)

if(MSVC)
    add_compile_options(/Zc:preprocessor)
endif()

if(WIN32)
    # On Windows, vcpkg handles wxWidgets and Asio automatically.
    # find_package(asio CONFIG) is the vcpkg standard for Asio.
    find_package(asio CONFIG REQUIRED)

    if(MINGW)
        find_program(wxWidgets_CONFIG_EXECUTABLE
            NAMES msw-unicode-3.3 wx-config
            PATHS "${CMAKE_BINARY_DIR}/vcpkg_installed/${VCPKG_TARGET_TRIPLET}/lib/wx/config"
            NO_DEFAULT_PATH
        )
        if(NOT wxWidgets_CONFIG_EXECUTABLE)
            message(FATAL_ERROR "Could not find vcpkg wx-config for MinGW.")
        endif()

        execute_process(
            COMMAND "${wxWidgets_CONFIG_EXECUTABLE}" --cppflags
            OUTPUT_VARIABLE wxWidgets_CPPFLAGS
            OUTPUT_STRIP_TRAILING_WHITESPACE
            COMMAND_ERROR_IS_FATAL ANY
        )
        execute_process(
            COMMAND "${wxWidgets_CONFIG_EXECUTABLE}" --libs net,gl,core,base,adv
            OUTPUT_VARIABLE wxWidgets_LIBS
            OUTPUT_STRIP_TRAILING_WHITESPACE
            COMMAND_ERROR_IS_FATAL ANY
        )
        separate_arguments(wxWidgets_CPPFLAGS NATIVE_COMMAND "${wxWidgets_CPPFLAGS}")
        separate_arguments(wxWidgets_LIBS NATIVE_COMMAND "${wxWidgets_LIBS}")
    else()
        find_package(wxWidgets REQUIRED net gl core base adv)
    endif()
    
    # Define Windows-specific libraries (added user32 for SendInput)
    set(PLATFORM_LIBS ws2_32 wsock32 user32)
    
    # MSVC vs MinGW resource handling
    if(MSVC)
        set(PLATFORM_SRC "src/input/win32/windowsinputmanager.cpp" "res/resource.rc")
    else()
        set(PLATFORM_SRC "src/input/win32/windowsinputmanager.cpp" "res/resource.rc")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wl,-subsystem,windows")
    endif()
else()
    # Linux: Use system-installed wxWidgets
    find_package(wxWidgets REQUIRED net gl core base adv)
    set(PLATFORM_SRC "src/input/linux/linuxinputmanager.cpp")
    set(PLATFORM_LIBS "")

    find_path(ASIO_INCLUDE_DIR asio.hpp
        PATHS
            ${ASIO_PATH}/include
            /usr/include
            /usr/local/include
    )
    if(NOT ASIO_INCLUDE_DIR)
        message(FATAL_ERROR "Could not find asio.hpp. Install libasio-dev or set ASIO_PATH.")
    endif()
endif()

# Collect all sources but avoid double-including input managers
file(GLOB_RECURSE ALL_SRC src/*.cpp)

list(REMOVE_ITEM ALL_SRC
    "${CMAKE_CURRENT_SOURCE_DIR}/src/input/linux/linuxinputmanager.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/src/input/win32/windowsinputmanager.cpp"
)

add_executable(Mousedroid WIN32 ${ALL_SRC} ${PLATFORM_SRC})



if(MINGW)
    target_compile_options(Mousedroid PRIVATE ${wxWidgets_CPPFLAGS})
    target_compile_definitions(Mousedroid PRIVATE UNICODE _UNICODE)
    target_link_libraries(Mousedroid PRIVATE ${wxWidgets_LIBS})
elseif(wxWidgets_FOUND)
    include(${wxWidgets_USE_FILE})
    target_link_libraries(Mousedroid PRIVATE ${wxWidgets_LIBRARIES})
endif()

target_link_libraries(Mousedroid PRIVATE ${PLATFORM_LIBS})

if(WIN32)
    # vcpkg-installed asio provides an imported target
    target_link_libraries(Mousedroid PRIVATE asio::asio)
else()
    target_include_directories(Mousedroid PRIVATE ./src ${ASIO_INCLUDE_DIR})
endif()

target_include_directories(Mousedroid PRIVATE ./src)
