#
# Copyright 2014-2023 Real Logic Limited.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

find_package(Java REQUIRED)

add_definitions(-DAERON_ALL_JAR="${AERON_ALL_JAR}")
add_definitions(-DJAVA_EXECUTABLE="${Java_JAVA_EXECUTABLE}")
add_definitions(-DARCHIVE_DIR="${CMAKE_CURRENT_BINARY_DIR}/archive")
add_definitions(-DJAVA_MAJOR_VERSION=${Java_VERSION_MAJOR})
add_definitions(-DJAVA_MINOR_VERSION=${Java_VERSION_MINOR})

function(aeron_archive_client_test name file)
    add_executable(${name} TestArchive.h ${file} ${SOURCE} ${HEADERS})
    add_dependencies(${name} gmock aeron-all-jar)
    target_include_directories(${name} PUBLIC ${AERON_CLIENT_SOURCE_PATH})
    target_include_directories(${name} PUBLIC ${AERON_ARCHIVE_SOURCE_PATH})
    target_include_directories(${name} PUBLIC ${ARCHIVE_CODEC_TARGET_DIR})
    target_link_libraries(${name} aeron_client aeron_archive_client gmock_main ${CMAKE_THREAD_LIBS_INIT})
    add_test(NAME ${name} COMMAND ${name})
    set_tests_properties(${name} PROPERTIES TIMEOUT 60)
    set_tests_properties(${name} PROPERTIES RUN_SERIAL TRUE)

    set(wrapper_name "${name}W")
    add_executable(${wrapper_name} TestArchive.h ${file} ${SOURCE} ${HEADERS})
    add_dependencies(${wrapper_name} gmock aeron-all-jar)
    target_include_directories(${wrapper_name} PUBLIC ${AERON_C_CLIENT_SOURCE_PATH})
    target_include_directories(${wrapper_name} PUBLIC ${AERON_CLIENT_WRAPPER_SOURCE_PATH})
    target_include_directories(${wrapper_name} PUBLIC ${AERON_ARCHIVE_SOURCE_PATH})
    target_include_directories(${wrapper_name} PUBLIC ${ARCHIVE_CODEC_TARGET_DIR})
    target_link_libraries(${wrapper_name} aeron_client_wrapper aeron_archive_client_wrapper gmock_main ${CMAKE_THREAD_LIBS_INIT})
    add_test(NAME ${wrapper_name} COMMAND ${name})
    set_tests_properties(${wrapper_name} PROPERTIES TIMEOUT 60)
    set_tests_properties(${wrapper_name} PROPERTIES RUN_SERIAL TRUE)
endfunction()

if (AERON_UNIT_TESTS)
    aeron_archive_client_test(archiveTest AeronArchiveTest.cpp)
endif ()

