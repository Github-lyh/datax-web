package com.wugui.datax.admin.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.wugui.datatx.core.biz.model.ReturnT;
import com.wugui.datatx.core.enums.ExecutorBlockStrategyEnum;
import com.wugui.datatx.core.glue.GlueTypeEnum;
import com.wugui.datatx.core.util.DateUtil;
import com.wugui.datax.admin.core.cron.CronExpression;
import com.wugui.datax.admin.core.route.ExecutorRouteStrategyEnum;
import com.wugui.datax.admin.core.thread.JobScheduleHelper;
import com.wugui.datax.admin.core.trigger.JobTrigger;
import com.wugui.datax.admin.core.util.I18nUtil;
import com.wugui.datax.admin.dto.*;
import com.wugui.datax.admin.dto.chain.ChainDto;
import com.wugui.datax.admin.dto.chain.LineDto;
import com.wugui.datax.admin.entity.*;
import com.wugui.datax.admin.mapper.*;
import com.wugui.datax.admin.service.DatasourceQueryService;
import com.wugui.datax.admin.service.DataxJsonService;
import com.wugui.datax.admin.service.JobDatasourceService;
import com.wugui.datax.admin.service.JobService;
import com.wugui.datax.admin.tool.query.BaseQueryTool;
import com.wugui.datax.admin.tool.query.QueryToolFactory;
import com.wugui.datax.admin.util.DateFormatUtils;
import com.wugui.datax.admin.util.JdbcConstants;
import com.wugui.datax.admin.util.JsonUtil;
import com.wugui.datax.admin.util.StringUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.jdo.annotations.Transactional;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.*;

/**
 * core job action for xxl-job
 *
 * @author xuxueli 2016-5-28 15:30:33
 */
@Service
public class JobServiceImpl implements JobService {
    private static Logger logger = LoggerFactory.getLogger(JobServiceImpl.class);

    @Resource
    private JobGroupMapper jobGroupMapper;
    @Resource
    private JobInfoMapper jobInfoMapper;
    @Resource
    private JobInfoChainMapper jobInfoChainMapper;
    @Resource
    private JobLogMapper jobLogMapper;
    @Resource
    private JobLogGlueMapper jobLogGlueMapper;
    @Resource
    private JobLogReportMapper jobLogReportMapper;
    @Resource
    private DatasourceQueryService datasourceQueryService;
    @Resource
    private JobTemplateMapper jobTemplateMapper;
    @Resource
    private DataxJsonService dataxJsonService;
    @Resource
    private JobDatasourceService jobDatasourceService;

    @Override
    public Map<String, Object> pageList(int start, int length, int jobGroup, int triggerStatus, String jobDesc, String glueType, int userId, Integer[] projectIds, int chainFlag) {

        // page list
        List<JobInfo> list = jobInfoMapper.pageList(start, length, jobGroup, triggerStatus, jobDesc, glueType, userId, projectIds, chainFlag);
        int list_count = jobInfoMapper.pageListCount(start, length, jobGroup, triggerStatus, jobDesc, glueType, userId, projectIds, chainFlag);

        // package result
        Map<String, Object> maps = new HashMap<>();
        maps.put("recordsTotal", list_count);        // 总记录数
        maps.put("recordsFiltered", list_count);    // 过滤后的总记录数
        maps.put("data", list);                    // 分页列表
        return maps;
    }

    public List<JobInfo> list() {
        return jobInfoMapper.findAll();
    }

    @Override
    public ReturnT<String> add(JobInfo jobInfo) {
        // valid
        JobGroup group = jobGroupMapper.load(jobInfo.getJobGroup());
        if (group == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_choose") + I18nUtil.getString("jobinfo_field_jobgroup")));
        }
        if (!CronExpression.isValidExpression(jobInfo.getJobCron())) {
            return new ReturnT<>(ReturnT.FAIL_CODE, I18nUtil.getString("jobinfo_field_cron_invalid"));
        }
        if (jobInfo.getGlueType().equals(GlueTypeEnum.BEAN.getDesc()) && jobInfo.getJobJson().trim().length() <= 2) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobinfo_field_jobjson")));
        }
        if (jobInfo.getJobDesc() == null || jobInfo.getJobDesc().trim().length() == 0) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobinfo_field_jobdesc")));
        }
        if (jobInfo.getUserId() == 0) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobinfo_field_author")));
        }
        if (ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null) == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("jobinfo_field_executorRouteStrategy") + I18nUtil.getString("system_invalid")));
        }
        if (ExecutorBlockStrategyEnum.match(jobInfo.getExecutorBlockStrategy(), null) == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("jobinfo_field_executorBlockStrategy") + I18nUtil.getString("system_invalid")));
        }
        if (GlueTypeEnum.match(jobInfo.getGlueType()) == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("jobinfo_field_gluetype") + I18nUtil.getString("system_invalid")));
        }
        if (GlueTypeEnum.BEAN == GlueTypeEnum.match(jobInfo.getGlueType()) && (jobInfo.getExecutorHandler() == null || jobInfo.getExecutorHandler().trim().length() == 0)) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_input") + "JobHandler"));
        }


        if (StringUtils.isBlank(jobInfo.getReplaceParamType()) || !DateFormatUtils.formatList().contains(jobInfo.getReplaceParamType())) {
            jobInfo.setReplaceParamType(DateFormatUtils.TIMESTAMP);
        }

        // fix "\r" in shell
        if (GlueTypeEnum.GLUE_SHELL == GlueTypeEnum.match(jobInfo.getGlueType()) && jobInfo.getGlueSource() != null) {
            jobInfo.setGlueSource(jobInfo.getGlueSource().replaceAll("\r", ""));
        }

        // ChildJobId valid
        if (jobInfo.getChildJobId() != null && jobInfo.getChildJobId().trim().length() > 0) {
            String[] childJobIds = jobInfo.getChildJobId().split(",");
            for (String childJobIdItem : childJobIds) {
                if (StringUtils.isNotBlank(childJobIdItem) && isNumeric(childJobIdItem) && Integer.parseInt(childJobIdItem) > 0) {
                    JobInfo childJobInfo = jobInfoMapper.loadById(Integer.parseInt(childJobIdItem));
                    if (childJobInfo == null) {
                        return new ReturnT<String>(ReturnT.FAIL_CODE,
                                MessageFormat.format((I18nUtil.getString("jobinfo_field_childJobId") + "({0})" + I18nUtil.getString("system_not_found")), childJobIdItem));
                    }
                } else {
                    return new ReturnT<String>(ReturnT.FAIL_CODE,
                            MessageFormat.format((I18nUtil.getString("jobinfo_field_childJobId") + "({0})" + I18nUtil.getString("system_invalid")), childJobIdItem));
                }
            }

            // join , avoid "xxx,,"
            String temp = "";
            for (String item : childJobIds) {
                temp += item + ",";
            }
            temp = temp.substring(0, temp.length() - 1);

            jobInfo.setChildJobId(temp);
        }

        // add in db
        jobInfo.setAddTime(new Date());
        jobInfo.setJobJson(jobInfo.getJobJson());
        jobInfo.setUpdateTime(new Date());
        jobInfo.setGlueUpdatetime(new Date());
        jobInfoMapper.save(jobInfo);
        if (jobInfo.getId() < 1) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("jobinfo_field_add") + I18nUtil.getString("system_fail")));
        }
        if(jobInfo.getChainFlag() == 1){
            // 保存chainJson
            String chainJson = "{\"id\":\""+jobInfo.getId()+"\",\"name\":\""+jobInfo.getJobDesc()+"\",\"nodeList\":[{\"id\":\""+jobInfo.getId()+"\",\"name\":\""+jobInfo.getJobDesc()+"\",\"type\":\"task\",\"left\":\"18px\",\"top\":\"223px\",\"ico\":\"el-icon-user-solid\",\"state\":\"success\"}],\"lineList\":[]}";
            jobInfoMapper.saveChainJson(String.valueOf(jobInfo.getId()),chainJson);
        }

        return new ReturnT<>(String.valueOf(jobInfo.getId()));
    }

    private boolean isNumeric(String str) {
        try {
            Integer.valueOf(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public ReturnT<String> update(JobInfo jobInfo) {

        // valid
        if (!CronExpression.isValidExpression(jobInfo.getJobCron())) {
            return new ReturnT<>(ReturnT.FAIL_CODE, I18nUtil.getString("jobinfo_field_cron_invalid"));
        }
        if (jobInfo.getGlueType().equals(GlueTypeEnum.BEAN.getDesc()) && jobInfo.getJobJson().trim().length() <= 2) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobinfo_field_jobjson")));
        }
        if (jobInfo.getJobDesc() == null || jobInfo.getJobDesc().trim().length() == 0) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobinfo_field_jobdesc")));
        }

        if (jobInfo.getProjectId() == 0) {
            return new ReturnT<String>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobinfo_field_jobproject")));
        }
        if (jobInfo.getUserId() == 0) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("system_please_input") + I18nUtil.getString("jobinfo_field_author")));
        }
        if (ExecutorRouteStrategyEnum.match(jobInfo.getExecutorRouteStrategy(), null) == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("jobinfo_field_executorRouteStrategy") + I18nUtil.getString("system_invalid")));
        }
        if (ExecutorBlockStrategyEnum.match(jobInfo.getExecutorBlockStrategy(), null) == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("jobinfo_field_executorBlockStrategy") + I18nUtil.getString("system_invalid")));
        }

        // ChildJobId valid
        if (jobInfo.getChildJobId() != null && jobInfo.getChildJobId().trim().length() > 0) {
            String[] childJobIds = jobInfo.getChildJobId().split(",");
            for (String childJobIdItem : childJobIds) {
                if (childJobIdItem != null && childJobIdItem.trim().length() > 0 && isNumeric(childJobIdItem)) {
                    JobInfo childJobInfo = jobInfoMapper.loadById(Integer.parseInt(childJobIdItem));
                    if (childJobInfo == null) {
                        return new ReturnT<String>(ReturnT.FAIL_CODE,
                                MessageFormat.format((I18nUtil.getString("jobinfo_field_childJobId") + "({0})" + I18nUtil.getString("system_not_found")), childJobIdItem));
                    }
                } else {
                    return new ReturnT<String>(ReturnT.FAIL_CODE,
                            MessageFormat.format((I18nUtil.getString("jobinfo_field_childJobId") + "({0})" + I18nUtil.getString("system_invalid")), childJobIdItem));
                }
            }

            // join , avoid "xxx,,"
            String temp = "";
            for (String item : childJobIds) {
                temp += item + ",";
            }
            temp = temp.substring(0, temp.length() - 1);

            jobInfo.setChildJobId(temp);
        }

        // group valid
        JobGroup jobGroup = jobGroupMapper.load(jobInfo.getJobGroup());
        if (jobGroup == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("jobinfo_field_jobgroup") + I18nUtil.getString("system_invalid")));
        }

        // stage job info
        JobInfo exists_jobInfo = jobInfoMapper.loadById(jobInfo.getId());
        if (exists_jobInfo == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, (I18nUtil.getString("jobinfo_field_id") + I18nUtil.getString("system_not_found")));
        }

        // next trigger time (5s后生效，避开预读周期)
        long nextTriggerTime = exists_jobInfo.getTriggerNextTime();
        if (exists_jobInfo.getTriggerStatus() == 1 && !jobInfo.getJobCron().equals(exists_jobInfo.getJobCron())) {
            try {
                Date nextValidTime = new CronExpression(jobInfo.getJobCron()).getNextValidTimeAfter(new Date(System.currentTimeMillis() + JobScheduleHelper.PRE_READ_MS));
                if (nextValidTime == null) {
                    return new ReturnT<String>(ReturnT.FAIL_CODE, I18nUtil.getString("jobinfo_field_cron_never_fire"));
                }
                nextTriggerTime = nextValidTime.getTime();
            } catch (ParseException e) {
                logger.error(e.getMessage(), e);
                return new ReturnT<String>(ReturnT.FAIL_CODE, I18nUtil.getString("jobinfo_field_cron_invalid") + " | " + e.getMessage());
            }
        }

        BeanUtils.copyProperties(jobInfo, exists_jobInfo);
        if (StringUtils.isBlank(jobInfo.getReplaceParamType())) {
            jobInfo.setReplaceParamType(DateFormatUtils.TIMESTAMP);
        }
        exists_jobInfo.setTriggerNextTime(nextTriggerTime);
        exists_jobInfo.setUpdateTime(new Date());

        if (GlueTypeEnum.BEAN.getDesc().equals(jobInfo.getGlueType())) {
            exists_jobInfo.setJobJson(jobInfo.getJobJson());
            exists_jobInfo.setGlueSource(null);
        } else {
            exists_jobInfo.setGlueSource(jobInfo.getGlueSource());
            exists_jobInfo.setJobJson(null);
        }

        if(jobInfo.getChainFlag() == 1 && StringUtils.isEmpty(exists_jobInfo.getChainJson())){
            // 保存chainJson
            String chainJson = "{\"id\":\""+jobInfo.getId()+"\",\"name\":\""+jobInfo.getJobDesc()+"\",\"nodeList\":[{\"id\":\""+jobInfo.getId()+"\",\"name\":\""+jobInfo.getJobDesc()+"\",\"type\":\"task\",\"left\":\"18px\",\"top\":\"223px\",\"ico\":\"el-icon-user-solid\",\"state\":\"success\"}],\"lineList\":[]}";
            exists_jobInfo.setChainJson(chainJson);
        }

        exists_jobInfo.setGlueUpdatetime(new Date());
        jobInfoMapper.update(exists_jobInfo);


        return ReturnT.SUCCESS;
    }

    @Override
    public ReturnT<String> remove(int id) {
        JobInfo xxlJobInfo = jobInfoMapper.loadById(id);
        if (xxlJobInfo == null) {
            return ReturnT.SUCCESS;
        }

        jobInfoMapper.delete(id);
        jobLogMapper.delete(id);
        jobLogGlueMapper.deleteByJobId(id);
        return ReturnT.SUCCESS;
    }

    @Override
    public ReturnT<String> start(int id) {
        JobInfo xxlJobInfo = jobInfoMapper.loadById(id);

        // next trigger time (5s后生效，避开预读周期)
        long nextTriggerTime = 0;
        try {
            Date nextValidTime = new CronExpression(xxlJobInfo.getJobCron()).getNextValidTimeAfter(new Date(System.currentTimeMillis() + JobScheduleHelper.PRE_READ_MS));
            if (nextValidTime == null) {
                return new ReturnT<String>(ReturnT.FAIL_CODE, I18nUtil.getString("jobinfo_field_cron_never_fire"));
            }
            nextTriggerTime = nextValidTime.getTime();
        } catch (ParseException e) {
            logger.error(e.getMessage(), e);
            return new ReturnT<String>(ReturnT.FAIL_CODE, I18nUtil.getString("jobinfo_field_cron_invalid") + " | " + e.getMessage());
        }

        xxlJobInfo.setTriggerStatus(1);
        xxlJobInfo.setTriggerLastTime(0);
        xxlJobInfo.setTriggerNextTime(nextTriggerTime);

        xxlJobInfo.setUpdateTime(new Date());
        jobInfoMapper.update(xxlJobInfo);
        return ReturnT.SUCCESS;
    }

    @Override
    public ReturnT<String> stop(int id) {
        JobInfo jobInfo = jobInfoMapper.loadById(id);

        jobInfo.setTriggerStatus(0);
        jobInfo.setTriggerLastTime(0);
        jobInfo.setTriggerNextTime(0);

        jobInfo.setUpdateTime(new Date());
        jobInfoMapper.update(jobInfo);
        return ReturnT.SUCCESS;
    }

    @Override
    public Map<String, Object> dashboardInfo() {

        int jobInfoCount = jobInfoMapper.findAllCount();
        int jobLogCount = 0;
        int jobLogSuccessCount = 0;
        JobLogReport jobLogReport = jobLogReportMapper.queryLogReportTotal();
        if (jobLogReport != null) {
            jobLogCount = jobLogReport.getRunningCount() + jobLogReport.getSucCount() + jobLogReport.getFailCount();
            jobLogSuccessCount = jobLogReport.getSucCount();
        }

        // executor count
        Set<String> executorAddressSet = new HashSet<>();
        List<JobGroup> groupList = jobGroupMapper.findAll();

        if (groupList != null && !groupList.isEmpty()) {
            for (JobGroup group : groupList) {
                if (group.getRegistryList() != null && !group.getRegistryList().isEmpty()) {
                    executorAddressSet.addAll(group.getRegistryList());
                }
            }
        }

        int executorCount = executorAddressSet.size();

        Map<String, Object> dashboardMap = new HashMap<>();
        dashboardMap.put("jobInfoCount", jobInfoCount);
        dashboardMap.put("jobLogCount", jobLogCount);
        dashboardMap.put("jobLogSuccessCount", jobLogSuccessCount);
        dashboardMap.put("executorCount", executorCount);
        return dashboardMap;
    }

    @Override
    public ReturnT<Map<String, Object>> chartInfo() {
        // process
        List<String> triggerDayList = new ArrayList<String>();
        List<Integer> triggerDayCountRunningList = new ArrayList<Integer>();
        List<Integer> triggerDayCountSucList = new ArrayList<Integer>();
        List<Integer> triggerDayCountFailList = new ArrayList<Integer>();
        int triggerCountRunningTotal = 0;
        int triggerCountSucTotal = 0;
        int triggerCountFailTotal = 0;

        List<JobLogReport> logReportList = jobLogReportMapper.queryLogReport(DateUtil.addDays(new Date(), -7), new Date());

        if (logReportList != null && logReportList.size() > 0) {
            for (JobLogReport item : logReportList) {
                String day = DateUtil.formatDate(item.getTriggerDay());
                int triggerDayCountRunning = item.getRunningCount();
                int triggerDayCountSuc = item.getSucCount();
                int triggerDayCountFail = item.getFailCount();

                triggerDayList.add(day);
                triggerDayCountRunningList.add(triggerDayCountRunning);
                triggerDayCountSucList.add(triggerDayCountSuc);
                triggerDayCountFailList.add(triggerDayCountFail);

                triggerCountRunningTotal += triggerDayCountRunning;
                triggerCountSucTotal += triggerDayCountSuc;
                triggerCountFailTotal += triggerDayCountFail;
            }
        } else {
            for (int i = -6; i <= 0; i++) {
                triggerDayList.add(DateUtil.formatDate(DateUtil.addDays(new Date(), i)));
                triggerDayCountRunningList.add(0);
                triggerDayCountSucList.add(0);
                triggerDayCountFailList.add(0);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("triggerDayList", triggerDayList);
        result.put("triggerDayCountRunningList", triggerDayCountRunningList);
        result.put("triggerDayCountSucList", triggerDayCountSucList);
        result.put("triggerDayCountFailList", triggerDayCountFailList);

        result.put("triggerCountRunningTotal", triggerCountRunningTotal);
        result.put("triggerCountSucTotal", triggerCountSucTotal);
        result.put("triggerCountFailTotal", triggerCountFailTotal);

        return new ReturnT<>(result);
    }


    @Override
    public ReturnT<String> batchAdd(DataXBatchJsonBuildDto dto) throws IOException {

        String key = "system_please_choose";
        List<String> rdTables = dto.getReaderTables();
        List<String> wrTables = dto.getWriterTables();
        if (dto.getReaderDatasourceId() == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, I18nUtil.getString(key) + I18nUtil.getString("jobinfo_field_readerDataSource"));
        }
        if (dto.getWriterDatasourceId() == null) {
            return new ReturnT<>(ReturnT.FAIL_CODE, I18nUtil.getString(key) + I18nUtil.getString("jobinfo_field_writerDataSource"));
        }
        if (rdTables.size() != wrTables.size()) {
            return new ReturnT<>(ReturnT.FAIL_CODE, I18nUtil.getString("json_build_inconsistent_number_r_w_tables"));
        }

        DataXJsonBuildDto jsonBuild = new DataXJsonBuildDto();

        List<String> rdTablesBack = new ArrayList<>(rdTables);
        List<String> wrTablesBack = new ArrayList<>(wrTables);

        if (StringUtils.isNotBlank(dto.getReaderSchema())) {
            rdTables.clear();
            rdTablesBack.stream().forEach(tableName -> {
                String prefix = dto.getReaderSchema() + ".";
                if (tableName.startsWith(prefix)) {
                    rdTables.add(tableName);
                } else {
                    rdTables.add(prefix + tableName);
                }
            });
        }
        if (StringUtils.isNotBlank(dto.getWriterSchema())) {
            wrTables.clear();
            wrTablesBack.stream().forEach(tableName -> {
                String prefix = dto.getWriterSchema() + ".";
                if (tableName.startsWith(prefix)) {
                    wrTables.add(tableName);
                } else {
                    wrTables.add(prefix + tableName);
                }
            });
        }

        List<String> rColumns;
        List<String> wColumns;
        for (int i = 0; i < rdTables.size(); i++) {
            rColumns = datasourceQueryService.getColumns(dto.getReaderDatasourceId(), rdTables.get(i));
            wColumns = datasourceQueryService.getColumns(dto.getWriterDatasourceId(), wrTables.get(i));

            jsonBuild.setReaderDatasourceId(dto.getReaderDatasourceId());
            jsonBuild.setWriterDatasourceId(dto.getWriterDatasourceId());

            jsonBuild.setReaderColumns(rColumns);
            jsonBuild.setWriterColumns(wColumns);

            jsonBuild.setRdbmsReader(dto.getRdbmsReader());
            jsonBuild.setRdbmsWriter(dto.getRdbmsWriter());

            JobDatasource readerDatasource = jobDatasourceService.getById(dto.getReaderDatasourceId());
            JobDatasource writerDatasource = jobDatasourceService.getById(dto.getWriterDatasourceId());

            if (dto.getHiveReader() != null && JdbcConstants.HIVE.equals(readerDatasource.getDatasource())) {
                // 构建hiveWriterDto对象
                HiveReaderDto hiveReaderDto = dto.getHiveReader();
                BaseQueryTool queryTool = QueryToolFactory.getByDbType(readerDatasource);
                String ddl = queryTool.getTableDDL(wrTables.get(i));
                if (ddl.contains("ORCFILE") || ddl.contains("orcfile")) {
                    hiveReaderDto.setReaderFileType("orc");
                } else {
                    hiveReaderDto.setReaderFileType("text");
                }
                String tag = "FIELDS TERMINATED BY ";
                String fieldDelimiter = StringUtil.find(ddl, tag);
                hiveReaderDto.setReaderFieldDelimiter(fieldDelimiter);
                String location = StringUtil.find(ddl, "LOCATION");
                hiveReaderDto.setReaderDefaultFS(location.substring(0, StringUtil.getCharacterPosition(location, "/", 3)));
                hiveReaderDto.setReaderPath(location.substring(StringUtil.getCharacterPosition(location, "/", 3)));
                jsonBuild.setHiveReader(hiveReaderDto);
            }

            if (dto.getHiveWriter() != null && JdbcConstants.HIVE.equals(writerDatasource.getDatasource())) {
                // 构建hiveWriterDto对象
                HiveWriterDto hiveWriterDto = dto.getHiveWriter();
                BaseQueryTool queryTool = QueryToolFactory.getByDbType(writerDatasource);
                String ddl = queryTool.getTableDDL(wrTables.get(i));
                if (ddl.contains("ORCFILE") || ddl.contains("orcfile")) {
                    hiveWriterDto.setWriterFileType("orc");
                } else {
                    hiveWriterDto.setWriterFileType("text");
                }
                String tag = "FIELDS TERMINATED BY ";
                String fieldDelimiter = StringUtil.find(ddl, tag);
                hiveWriterDto.setWriteFieldDelimiter(fieldDelimiter);
                String location = StringUtil.find(ddl, "LOCATION");
                hiveWriterDto.setWriterDefaultFS(location.substring(0, StringUtil.getCharacterPosition(location, "/", 3)));
                hiveWriterDto.setWriterFileName(location.substring(location.lastIndexOf("/") + 1));
                hiveWriterDto.setWriterPath(location.substring(StringUtil.getCharacterPosition(location, "/", 3)));
                jsonBuild.setHiveWriter(hiveWriterDto);
            }

            List<String> rdTable = new ArrayList<>();
            rdTable.add(rdTables.get(i));
            jsonBuild.setReaderTables(rdTable);

            List<String> wdTable = new ArrayList<>();
            wdTable.add(wrTables.get(i));
            jsonBuild.setWriterTables(wdTable);

            String json = dataxJsonService.buildJobJson(jsonBuild);
            json = json.replace("\\\\", "\\");
            JobTemplate jobTemplate = jobTemplateMapper.loadById(dto.getTemplateId());
            JobInfo jobInfo = new JobInfo();
            BeanUtils.copyProperties(jobTemplate, jobInfo);
            jobInfo.setJobJson(json);
            jobInfo.setJobDesc(rdTables.get(i));
            jobInfo.setAddTime(new Date());
            jobInfo.setUpdateTime(new Date());
            jobInfo.setGlueUpdatetime(new Date());
            jobInfoMapper.save(jobInfo);
        }
        return ReturnT.SUCCESS;
    }

    @Override
    public List<JobConnDto> connList(List<Integer> ids) {
        List<JobInfo> infos = jobInfoMapper.loadByIds(ids);
        List<JobConnDto> list = new ArrayList<>();
        for (JobInfo info : infos) {

            if (StringUtils.isNotBlank(info.getChildJobId())) {
                String[] childIds = info.getChildJobId().split(",");
                for (String id : childIds) {
                    JobConnDto jobConnDto = new JobConnDto();
                    jobConnDto.setSourceId(info.getId());
                    jobConnDto.setTargetId(Integer.valueOf(id));
                    list.add(jobConnDto);
                }
            }
        }
        return list;
    }

    @Override
    public ReturnT<Long> getMaxId(Integer datasourceId, String tableName, String primaryKey) {
        Long result = JobTrigger.getMaxId(datasourceId, tableName, primaryKey);
        return new ReturnT<>(result);
    }

    @Override
    public ReturnT<Date> getMaxTime(Integer datasourceId, String tableName, String primaryKey) {
        Date result = JobTrigger.getMaxTime(datasourceId, tableName, primaryKey);
        return new ReturnT<>(result);
    }

    @Override
    public List<ProjectDto> getNodeMenuList() {
        return jobInfoMapper.getNodeMenuList();
    }

    @Override
    @Transactional
    public void saveChain(ChainDto dto) {
        try {
            jobInfoMapper.saveChainJson(dto.getId(), JsonUtil.formatObjectToJson(dto));
            jobInfoChainMapper.removeAll(dto.getId());
            // 保存关系
            if (CollectionUtils.isNotEmpty(dto.getLineList())) {
                for (LineDto lineDto : dto.getLineList()) {
                    jobInfoChainMapper.insert(getId(lineDto.getFrom()), getId(lineDto.getTo()), dto.getId());
                }
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private Integer getId(String value){
        if(StringUtils.isNotBlank(value)){
            if(value.contains("-")){
                value = value.substring(value.lastIndexOf("-") + 1);
            }
            return Integer.valueOf(value);
        }
        return null;
    }

    @Override
    public void saveChainJson(ChainDto dto) {
        try {
            jobInfoMapper.saveChainJson(dto.getId(), JsonUtil.formatObjectToJson(dto));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getChainJson(String id) {
        return jobInfoMapper.getChainJson(id);
    }
}
