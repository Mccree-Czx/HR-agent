package com.hragent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("score_record")
public class ScoreRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    private Long jdId;

    private Integer score;

    private String reason;

    private String ruleVersion;

    private String model;

    private Integer tokenUsage;

    private LocalDateTime createdAt;
}
