/*
 * Copyright 2014-2023 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef AERON_ERROR_FLYWEIGHT_H
#define AERON_ERROR_FLYWEIGHT_H

#include <cstdint>
#include <cstddef>
#include "Flyweight.h"

namespace aeron { namespace command
{

/**
 * Control message flyweight for any errors sent from driver to clients
 *
 * <p>
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |              Offending Command Correlation ID                 |
 * |                                                               |
 * +---------------------------------------------------------------+
 * |                        Error Code                             |
 * +---------------------------------------------------------------+
 * |                   Error Message Length                        |
 * +---------------------------------------------------------------+
 * |                       Error Message                          ...
 * ...                                                             |
 * +---------------------------------------------------------------+
 */

#pragma pack(push)
#pragma pack(4)
struct ErrorResponseDefn
{
    std::int64_t offendingCommandCorrelationId;
    std::int32_t errorCode;
    std::int32_t errorMessageLength;
    std::int8_t errorMessageData[1];
};
#pragma pack(pop)

static const std::int32_t ERROR_CODE_UNKNOWN_CODE_VALUE = -1;

static const std::int32_t ERROR_CODE_UNUSED = 0;
static const std::int32_t ERROR_CODE_INVALID_CHANNEL = 1;
static const std::int32_t ERROR_CODE_UNKNOWN_SUBSCRIPTION = 2;
static const std::int32_t ERROR_CODE_UNKNOWN_PUBLICATION = 3;
static const std::int32_t ERROR_CODE_CHANNEL_ENDPOINT_ERROR = 4;
static const std::int32_t ERROR_CODE_UNKNOWN_COUNTER = 5;
static const std::int32_t ERROR_CODE_UNKNOWN_COMMAND_TYPE_ID = 6;
static const std::int32_t ERROR_CODE_MALFORMED_COMMAND = 7;
static const std::int32_t ERROR_CODE_NOT_SUPPORTED = 8;
static const std::int32_t ERROR_CODE_UNKNOWN_HOST = 9;
static const std::int32_t ERROR_CODE_RESOURCE_TEMPORARILY_UNAVAILABLE = 10;
static const std::int32_t ERROR_CODE_GENERIC_ERROR = 11;

class ErrorResponseFlyweight : public Flyweight<ErrorResponseDefn>
{
public:
    typedef ErrorResponseFlyweight this_t;

    inline ErrorResponseFlyweight(concurrent::AtomicBuffer &buffer, util::index_t offset) :
        Flyweight<ErrorResponseDefn>(buffer, offset)
    {
    }

    inline std::int64_t offendingCommandCorrelationId() const
    {
        return m_struct.offendingCommandCorrelationId;
    }

    inline std::int32_t errorCode() const
    {
        return m_struct.errorCode;
    }

    inline std::string errorMessage() const
    {
        return stringGet(static_cast<util::index_t>(offsetof(ErrorResponseDefn, errorMessageLength)));
    }

    inline util::index_t length() const
    {
        return static_cast<util::index_t>(offsetof(ErrorResponseDefn, errorMessageData) + m_struct.errorMessageLength);
    }
};

}}
#endif
