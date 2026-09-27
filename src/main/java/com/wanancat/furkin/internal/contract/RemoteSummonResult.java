package com.wanancat.furkin.internal.contract;

/** 远距召唤的服务端结果；不进入网络线格式。 */
public enum RemoteSummonResult {
    COMPLETED_TELEPORT,
    COMPLETED_REBUILD,
    NOT_FOUND,
    NOT_OWNER,
    NOT_ALIVE,
    ACTIVE_LIMIT,
    REBUILD_FAILED,
    DISABLED,
    INVALID_STATE,
    ALREADY_PENDING,
    TOO_MANY_PENDING,
    COOLDOWN,
    NO_POSITION,
    DIMENSION_MISSING,
    ENTITY_UNRESOLVED,
    DUPLICATE_CONFLICT,
    CHUNK_LOAD_FAILED,
    TIMEOUT,
    TELEPORT_FAILED,
    CANCELLED,
    /** 请求已受理并进入 WAIT_CHUNK，不等同于最终成功。 */
    PENDING
}