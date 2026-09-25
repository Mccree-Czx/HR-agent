package com.hragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hragent.entity.Candidate;
import com.hragent.entity.GreetingRecord;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.ResumeFile;
import com.hragent.executor.JsonExtractor;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.GreetingRecordMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.ResumeFileMapper;
import com.hragent.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 简历收集(阶段 4):
 * 1. 同意状态检测:chatlist 查对方是否回复(direction=0 为对方发来)
 * 2. 有回复 → request-resume 索要简历(需先 greet 建立会话)
 * 3. 简历文件落库:StorageService(MinIO/本地)+ resume_file 元数据(按候选人去重)
 */
@Slf4j
@Service
public class ResumeCollectService {

    private final GreetingRecordMapper greetingMapper;
    private final CandidateMapper candidateMapper;
    private final LiepinAccountMapper accountMapper;
    private final ResumeFileMapper resumeFileMapper;
    private final LiepinCommandService commandService;
    private final StorageService storageService;

    public ResumeCollectService(GreetingRecordMapper greetingMapper, CandidateMapper candidateMapper,
                                LiepinAccountMapper accountMapper, ResumeFileMapper resumeFileMapper,
                                LiepinCommandService commandService, StorageService storageService) {
        this.greetingMapper = greetingMapper;
        this.candidateMapper = candidateMapper;
        this.accountMapper = accountMapper;
        this.resumeFileMapper = resumeFileMapper;
        this.commandService = commandService;
        this.storageService = storageService;
    }

    /**
     * 对岗位下已打招呼(SENT/REQUESTED)且未入库简历的候选人:
     * 检测聊天回复 → 有回复则索要简历。
     * 返回处理数。
     */
    @Transactional
    public int collectForJd(Long jdId, int limit) {
        List<GreetingRecord> records = greetingMapper.selectList(new LambdaQueryWrapper<GreetingRecord>()
                .in(GreetingRecord::getStatus, "SENT", "REQUESTED", "AGREED")
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
        int processed = 0;
        for (GreetingRecord record : records) {
            Candidate candidate = candidateMapper.selectById(record.getCandidateId());
            if (candidate == null || candidate.getJdId() == null || !candidate.getJdId().equals(jdId)) {
                continue;
            }
            if (resumeFileMapper.selectCount(new LambdaQueryWrapper<ResumeFile>()
                    .eq(ResumeFile::getCandidateId, candidate.getId())) > 0) {
                continue; // 已入库
            }
            try {
                collectOne(record, candidate);
                processed++;
            } catch (Exception e) {
                log.warn("候选人 {} 简历收集失败: {}", candidate.getId(), e.getMessage());
            }
        }
        return processed;
    }

    /** 单个候选人:检测回复 + 索要简历 */
    public void collectOne(GreetingRecord record, Candidate candidate) {
        LiepinAccount account = accountMapper.selectById(record.getAccountId());
        if (account == null) {
            log.warn("候选人 {} 的打招呼账号 {} 不存在", candidate.getId(), record.getAccountId());
            return;
        }
        Duration timeout = Duration.ofMinutes(2);

        // 1. 同意状态检测:查聊天列表匹配该候选人
        boolean replied = checkReply(account, candidate, timeout);

        // 2. 有回复且未索要过 → 索要简历
        if (replied && !"REQUESTED".equals(record.getStatus()) && !"AGREED".equals(record.getStatus())) {
            requestResume(account, candidate, record, timeout);
            log.info("候选人 {} 已回复,索要处理状态: {}", candidate.getId(), record.getStatus());
        } else if (replied) {
            log.info("候选人 {} 已回复(已索要过简历,等待对方同意)", candidate.getId());
        } else {
            log.info("候选人 {} 尚未回复,跳过", candidate.getId());
        }
    }

    /** 通过 chatlist 检测候选人是否回复(对方发来消息 direction=0) */
    boolean checkReply(LiepinAccount account, Candidate candidate, Duration timeout) {
        List<JsonNode> chats = commandService.chatlist(account, timeout);
        JsonNode snapshot = JsonExtractor.parse(candidate.getSnapshot()).orElse(null);
        String userId = snapshot == null ? "" : snapshot.path("user_id").asText("");
        String imId = snapshot == null ? "" : snapshot.path("im_id").asText("");
        for (JsonNode chat : chats) {
            String chatUserId = chat.path("user_id").asText("");
            String chatImId = chat.path("im_id").asText("");
            boolean samePerson = (!userId.isBlank() && userId.equals(chatUserId))
                    || (!imId.isBlank() && imId.equals(chatImId));
            if (!samePerson) {
                continue;
            }
            // 实测:direction=1 为候选人发来的消息(如回复),direction=0 为我方/平台发出
            if ("1".equals(chat.path("direction").asText(""))) {
                return true;
            }
        }
        return false;
    }

    /** 索要简历并更新打招呼记录状态 */
    private void requestResume(LiepinAccount account, Candidate candidate,
                               GreetingRecord record, Duration timeout) {
        String resumeId = candidate.getResumeId();
        if (resumeId == null || resumeId.isBlank()) {
            log.warn("候选人 {} 缺少 resume_id,无法索要简历", candidate.getId());
            return;
        }
        Optional<JsonNode> result = commandService.requestResume(account, resumeId, timeout);
        boolean confirmed = result.filter(node -> node.path("success").asBoolean(false)
                && node.path("confirmed").asBoolean(false)).isPresent();
        if (!confirmed) {
            log.warn("候选人 {} 索要未获确认,保留原状态", candidate.getId());
            return;
        }
        record.setStatus("REQUESTED");
        greetingMapper.updateById(record);
        log.info("索要简历已确认(account={}, candidate={})", account.getId(), candidate.getId());
    }

    /**
     * 简历文件落库(适配点:文件字节来源待实测确认,可能是聊天下载/邮件附件):
     * 上传 StorageService + 写 resume_file 元数据,按候选人去重。
     */
    @Transactional
    public ResumeFile saveResumeFile(Long candidateId, String fileName, byte[] content, String contentType) {
        Candidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("候选人不存在: " + candidateId);
        }
        ResumeFile existing = resumeFileMapper.selectOne(new LambdaQueryWrapper<ResumeFile>()
                .eq(ResumeFile::getCandidateId, candidateId)
                .last("LIMIT 1"));
        String objectKey = buildObjectKey(candidateId, fileName);
        storageService.save(objectKey, content, contentType);
        String sha256 = sha256(content);

        if (existing != null) {
            existing.setBucket(objectKey);
            existing.setObjectKey(objectKey);
            existing.setFormat(extractFormat(fileName));
            existing.setSize((long) content.length);
            existing.setSha256(sha256);
            resumeFileMapper.updateById(existing);
            return existing;
        }
        ResumeFile file = new ResumeFile();
        file.setCandidateId(candidateId);
        file.setBucket(objectKey);
        file.setObjectKey(objectKey);
        file.setFormat(extractFormat(fileName));
        file.setSize((long) content.length);
        file.setSha256(sha256);
        resumeFileMapper.insert(file);
        return file;
    }

    private String buildObjectKey(Long candidateId, String fileName) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "resumes/" + date + "/" + candidateId + "-" + safeName(fileName);
    }

    private String safeName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "resume.pdf" : fileName;
        return name.replaceAll("[^\\w\\u4e00-\\u9fa5.-]", "_");
    }

    private String extractFormat(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            return "";
        }
    }
}
