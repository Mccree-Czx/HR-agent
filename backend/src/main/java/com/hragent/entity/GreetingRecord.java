package com.hragent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("greeting_record")
public class GreetingRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    private Long accountId;

    private String message;

    private String status;

    private String mode;

    private LocalDateTime createdAt;
}
