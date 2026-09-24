package com.hragent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("candidate")
public class Candidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String resumeId;

    private String name;

    /** 在线简历快照(结构化字段 JSON) */
    private String snapshot;

    private Integer score;

    private String passStatus;

    private Long jdId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
