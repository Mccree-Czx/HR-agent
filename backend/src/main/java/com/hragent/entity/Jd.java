package com.hragent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("jd")
public class Jd {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String externalJd;

    private String internalNotes;

    private Integer salaryMin;

    private Integer salaryMax;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
