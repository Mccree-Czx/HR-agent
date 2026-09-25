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

    /** 打招呼实际关联的猎聘职位 ID(来源岗位的 liepin_job_id,审计用) */
    private String liepinJobId;

    private String message;

    private String status;

    private String mode;

    private LocalDateTime createdAt;
}
