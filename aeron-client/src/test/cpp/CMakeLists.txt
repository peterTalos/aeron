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

set(TEST_HEADERS
    ClientConductorFixture.h
    EmbeddedMediaDriver.h
    util/TestUtils.h
    concurrent/MockAtomicBuffer.h)

set(TEST_SOURCE
    ClientConductorFixture.cpp
    concurrent/MockAtomicBuffer.cpp)

add_library(aeron_client_test STATIC ${TEST_SOURCE} ${TEST_HEADERS})
target_include_directories(aeron_client_test
    PUBLIC ${AERON_CLIENT_TEST_PATH})
target_link_libraries(aeron_client_test aeron_client gmock)
target_compile_definitions(aeron_client_test PUBLIC "_SILENCE_TR1_NAMESPACE_DEPRECATION_WARNING")
add_dependencies(aeron_client_test gmock)

if (MSVC AND ${CMAKE_VERSION} VERSION_LESS "3.13.0")
    target_compile_definitions(aeron_client_test PUBLIC "MSVC_FILE_IS_LOWER_CASE")
endif ()

function(aeron_client_test name file)
    add_executable(${name} ${file})
    add_dependencies(${name} gmock)
    target_link_libraries(${name} aeron_client aeron_driver aeron_client_test gmock_main ${CMAKE_THREAD_LIBS_INIT})
    target_compile_definitions(${name} PUBLIC "_SILENCE_TR1_NAMESPACE_DEPRECATION_WARNING")
    add_test(NAME ${name} COMMAND ${name})
endfunction()

if (AERON_UNIT_TESTS)
    aeron_client_test(clientConductorTest ClientConductorTest.cpp)
    aeron_client_test(publicationTest PublicationTest.cpp)
    aeron_client_test(exclusivePublicationTest ExclusivePublicationTest.cpp)
    aeron_client_test(imageTest ImageTest.cpp)
    aeron_client_test(fragmentAssemblyTest FragmentAssemblerTest.cpp)
    aeron_client_test(commandTest command/CommandTest.cpp)
    aeron_client_test(utilTest util/UtilTest.cpp)
    aeron_client_test(memoryMappedFileTest util/MemoryMappedFileTest.cpp)
    aeron_client_test(broadcastReceiverTest concurrent/BroadcastReceiverTest.cpp)
    aeron_client_test(broadcastTransmitterTest concurrent/BroadcastTransmitterTest.cpp)
    aeron_client_test(concurrentTest concurrent/ConcurrentTest.cpp)
    aeron_client_test(countersManagerTest concurrent/CountersManagerTest.cpp)
    aeron_client_test(termReaderTest concurrent/TermReaderTest.cpp)
    aeron_client_test(termBlockScannerTest concurrent/TermBlockScannerTest.cpp)
    aeron_client_test(termGapScannerTest concurrent/TermGapScannerTest.cpp)
    aeron_client_test(termScannerTest concurrent/TermScannerTest.cpp)
    aeron_client_test(manyToOneRingBufferTest concurrent/ManyToOneRingBufferTest.cpp)
    aeron_client_test(distinctErrorLogTest concurrent/DistinctErrorLogTest.cpp)
    aeron_client_test(errorLogReaderTest concurrent/ErrorLogReaderTest.cpp)
    aeron_client_test(oneToOneRingBufferTest concurrent/OneToOneRingBufferTest.cpp)
    aeron_client_test(channelUriStringBuilderTest ChannelUriStringBuilderTest.cpp)
    aeron_client_test(channelUriTest ChannelUriTest.cpp)
    aeron_client_test(cppSystemTest SystemTest.cpp)

    aeron_client_test(atomicArrayUpdaterTest concurrent/AtomicArrayUpdaterTest.cpp)
    set_tests_properties(atomicArrayUpdaterTest PROPERTIES TIMEOUT 60)
endif ()
