package com.hragent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("search_task")
public class SearchTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jdId;

    private Long accountId;

    private String keywords;

    private String status;

    private LocalDateTime leaseExpireAt;

    private Integer retryCount;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
