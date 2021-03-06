package com.jiafupeng.rest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author jiafupeng
 * @create 2020/10/26 9:44
 * @desc   接受 SonarQube webHooks 推送过来的信息
 *         由于逻辑简单，就没有进行分层处理
 **/

@RestController
@RequestMapping("/sonarQubeWebHooksRest")
@Slf4j
public class SonarQubeWebHooksRest {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${wechat.webhook.url}")
    private String wechatWebhookUrl;

    @Value("${local.server.ip}")
    private String localServerIp;

    @PostMapping("/receive")
    public String receive(@RequestBody Map<String, Object> paramMaps){
        log.info("receive SonarQube WebHooks, paramMaps: " + paramMaps.toString());
        // 获取gitlab提交信息
        CommitInfoDTO CommitInfo = getCommitInfo(paramMaps);
        log.info("CommitInfo: " + CommitInfo);
        // 获取检测报告数据
        TestReportDTO testReport = getTestReport(paramMaps);
        log.info("testReport: " + testReport);
        // 构建wechat机器人 post请求 body 数据(支持markdown)
        Map<String, Object> sendBodyMap = buildSendBody(CommitInfo, testReport);
        log.info("sendBodyMap: " + sendBodyMap);
        // 实际发送
        ResponseEntity responseEntity = restTemplate.postForEntity(wechatWebhookUrl, new HttpEntity(sendBodyMap, new HttpHeaders()), String.class);
        log.info("call wechat robots status: " + responseEntity.getStatusCode() + ", response body: " + responseEntity.getBody());
        return HttpStatus.OK.toString();
    }

    /**
     * 获取gitlab提交信息
     * @param paramMaps
     */
    private CommitInfoDTO getCommitInfo(Map<String, Object> paramMaps){
        Map<String, String> propertiesMaps = (Map)paramMaps.get("properties");
        String projectPath = propertiesMaps.get("sonar.analysis.CI_PROJECT_PATH");
        String branchName = propertiesMaps.get("sonar.analysis.CI_COMMIT_REF_NAME");
        String userName = propertiesMaps.get("sonar.analysis.GITLAB_USER_NAME");
        String userEmail = propertiesMaps.get("sonar.analysis.GITLAB_USER_EMAIL");

        return CommitInfoDTO.builder()
                .projectPath(projectPath)
                .branchName(branchName)
                .userName(userName)
                .userEmail(userEmail)
                .build();
    }

    /**
     * 获取检测报告数据
     * @param paramMaps
     * @return
     */
    private TestReportDTO getTestReport(Map<String, Object> paramMaps){
        TestReportDTO testReportDTO = new TestReportDTO();
        String analysedAt = (String)paramMaps.get("analysedAt");
        String sonarQubeProjectUrl = (String)((Map)paramMaps.get("project")).get("url");
        testReportDTO.setSonarQubeProjectUrl(sonarQubeProjectUrl);
        testReportDTO.setAnalysisStartTime(analysedAt);
        Map<String, Object> qualityGate = (Map)paramMaps.get("qualityGate");
        List<Map> conditions = (List)qualityGate.get("conditions");
        String rootStatus = (String)qualityGate.get("status");
        testReportDTO.setStatus(rootStatus);
        for(Map<String, String> map : conditions){
            String metric = map.get("metric");
            String value = map.get("value");
            String status = map.get("status");
            switch (metric){
                // 可靠性 - bugs
                case "new_reliability_rating":
                    testReportDTO.setReliability(value);
                    testReportDTO.setReliabilityStatus(status);
                    break;
                // 安全性 - 漏洞
                case "new_security_rating":
                    testReportDTO.setSecurity(value);
                    testReportDTO.setSecurityStatus(status);
                    break;
                // 可维护性 - 异味
                case "new_maintainability_rating":
                    testReportDTO.setMaintainability(value);
                    testReportDTO.setMaintainabilityStatus(status);
                    break;
                // 代码覆盖率
                case "new_coverage":
                    testReportDTO.setCoverage(value);
                    testReportDTO.setCoverageStatus(status);
                    break;
                // 重复率
                case "new_duplicated_lines_density":
                    testReportDTO.setDuplicated(value);
                    testReportDTO.setDuplicatedStatus(status);
                    break;
                // 安全热点
                case "new_security_hotspots_reviewed":
                    testReportDTO.setHotspots(value);
                    testReportDTO.setHotspotsStatus(status);
                    break;
                default:;
            }
        }

        return testReportDTO;
    }

    /**
     * 构建wechat机器人 post请求 body 数据(支持markdown)
     * @param CommitInfo
     * @param testReport
     * @return
     */
    private Map<String, Object> buildSendBody(CommitInfoDTO CommitInfo, TestReportDTO testReport){
        Map<String, Object> sendBodyMap = new HashMap<>(16);
        sendBodyMap.put("msgtype","markdown");
        Map<String, Object> ContentMap = new HashMap<>(16);
        String content = markDownTemplate(CommitInfo, testReport);
        ContentMap.put("content",content);
        ContentMap.put("mentioned_list", new String[]{CommitInfo.getUserName()});
        sendBodyMap.put("markdown",ContentMap);
        return sendBodyMap;
    }

    /**
     * 构建 post请求 body markdown语法
     * @param commitInfoDTO
     * @param testReportDTO
     * @return
     */
    private String markDownTemplate(CommitInfoDTO commitInfoDTO, TestReportDTO testReportDTO){
        StringBuilder sb = new StringBuilder();
        sb.append("### DM 代码检测通知\r\n");
        sb.append("> **项目**: ").append(commitInfoDTO.getProjectPath()).append("\r\n");
        sb.append("> **分支**: ").append(commitInfoDTO.getBranchName()).append("\r\n");
        sb.append("> **提交人信息**: ").append(commitInfoDTO.getUserName()).append(" / ").append(commitInfoDTO.getUserEmail()).append("\r\n");
        sb.append("> **提交时间**: ").append(testReportDTO.getAnalysisStartTime()).append("\r\n");
        sb.append("> **是否通过**: ").append(getStatusWithColor(testReportDTO.getStatus())).append("\r\n");;
        sb.append("> **可靠性(bugs)**: ").append(getStatusWithColor(testReportDTO.getReliability(), testReportDTO.getReliabilityStatus())).append("\r\n");
        sb.append("> **安全性(漏洞)**: ").append(getStatusWithColor(testReportDTO.getSecurity(), testReportDTO.getSecurityStatus())).append("\r\n");
        sb.append("> **可维护性(异样)**: ").append(getStatusWithColor(testReportDTO.getMaintainability(), testReportDTO.getMaintainabilityStatus())).append("\r\n");
        sb.append("> **代码覆盖率**: ").append(getStatusWithColor(testReportDTO.getCoverage(), testReportDTO.getCoverageStatus())).append("\r\n");
        sb.append("> **代码重复率**: ").append(getStatusWithColor(testReportDTO.getDuplicated(), testReportDTO.getDuplicatedStatus())).append("\r\n");
        sb.append("> **安全复审(安全热点)**: ").append(getStatusWithColor(testReportDTO.getHotspots(), testReportDTO.getHotspotsStatus())).append("\r\n");
        sb.append("--- \r\n");
        sb.append(" 详细检测结果请[点击查看](").append(getTransformUrl(testReportDTO.getSonarQubeProjectUrl())).append(")");
        return sb.toString();
    }

    /**
     * 添加markdown语法 字体变色
     * @param status
     * @return
     */
    private String getStatusWithColor(String status){
        if("OK".equalsIgnoreCase(status)){
            return "<font color='info'>通过</font>";
        }

        return "<font color='warning'>未通过</font>";
    }

    /**
     * 添加markdown语法 字体变色
     * @param status
     * @return
     */
    private String getStatusWithColor(String value, String status){
        if("NO_VALUE".equalsIgnoreCase(status)){
            return "<font color='commont'>空</font>";
        }

        value = valueFormat(value);

        if("OK".equalsIgnoreCase(status)){
            return "<font color='info'>" + value + "</font>";
        }

        return "<font color='warning'>" + value + "</font>";
    }

    /**
     * 根据返回数字判定等级
     * @param value
     * @return
     */
    private String valueFormat(String value){
        if(StringUtils.isEmpty(value)){
            return value;
        }
        switch (value){
            case "1" : value = "A"; break;
            case "2" : value = "B"; break;
            case "3" : value = "C"; break;
            case "4" : value = "D"; break;
            case "5" : value = "E"; break;
            case "6" : value = "F"; break;
            default:;
        }

        return value;
    }

    /**
     * 转换localhost => 真实IP
     * TODO 非常不好，后续优化
     * @param originalUrl
     * @return
     */
    private String getTransformUrl(String originalUrl){
        return originalUrl.replaceAll("localhost", localServerIp);
    }

    /**
     * gitlab提交信息
     */
    @Data
    @Builder
    private static class CommitInfoDTO{
        private String projectPath;
        private String branchName;
        private String userName;
        private String userEmail;
    }

    /**
     * 检测信息
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    private static class TestReportDTO {

        /**
         * 检测时间
         */
        private String analysisStartTime;

        /**
         * sonarQube 的检测面板
         */
        private String SonarQubeProjectUrl;

        /**
         * 是否成功
         */
        private String status;

        /**
         * 代码可靠性 - bug数量
         */
        private String reliability;

        /**
         * 代码可靠性状态
         */
        private String reliabilityStatus;

        /**
         * 代码安全性 - 漏洞
         */
        private String security;

        /**
         * 代码安全性状态
         */
        private String securityStatus;

        /**
         * 代码可维护性 - 异味
         */
        private String maintainability;

        /**
         * 代码可维护性状态
         */
        private String maintainabilityStatus;

        /**
         * 代码覆盖率
         */
        private String coverage;

        /**
         * 代码覆盖率状态
         */
        private String coverageStatus;

        /**
         * 代码重复率
         */
        private String duplicated;

        /**
         * 代码重复状态
         */
        private String duplicatedStatus;

        /**
         *  安全热点
         */
        private String hotspots;

        /**
         * 安全热点状态
         */
        private String hotspotsStatus;
    }
}
