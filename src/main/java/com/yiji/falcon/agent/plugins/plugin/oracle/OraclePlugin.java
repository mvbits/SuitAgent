/*
 * www.yiji.com Inc.
 * Copyright (c) 2016 All Rights Reserved
 */
package com.yiji.falcon.agent.plugins.plugin.oracle;
/*
 * 修订记录:
 * guqiu@yiji.com 2016-06-28 11:07 创建
 */

import com.yiji.falcon.agent.falcon.CounterType;
import com.yiji.falcon.agent.falcon.FalconReportObject;
import com.yiji.falcon.agent.falcon.MetricsType;
import com.yiji.falcon.agent.plugins.JDBCPlugin;
import com.yiji.falcon.agent.plugins.metrics.MetricsCommon;
import com.yiji.falcon.agent.plugins.util.PluginActivateType;
import com.yiji.falcon.agent.util.StringUtils;
import com.yiji.falcon.agent.vo.jdbc.JDBCUserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.yiji.falcon.agent.plugins.metrics.MetricsCommon.getMetricsName;

/**
 * @author guqiu@yiji.com
 */
public class OraclePlugin implements JDBCPlugin {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final ConcurrentHashMap<JDBCUserInfo,Connection> connectionCache = new ConcurrentHashMap<>();
    private final List<JDBCUserInfo> userInfoList = new ArrayList<>();
    private int step;
    private PluginActivateType pluginActivateType;

    @Override
    public String authorizationKeyPrefix() {
        return "Oracle";
    }

    /**
     * 获取JDBC连接
     *
     * @return
     */
    @Override
    public Collection<Connection> getConnections() throws SQLException, ClassNotFoundException {
        if(connectionCache.isEmpty()){
            String driver_jdbc = "oracle.jdbc.driver.OracleDriver";
            Class.forName(driver_jdbc);
            for (JDBCUserInfo userInfo : userInfoList) {
                connectionCache.put(userInfo,
                        DriverManager.getConnection(userInfo.getUrl(), userInfo.getUsername(), userInfo.getPassword()));
            }
            return connectionCache.values();
        }
        for (JDBCUserInfo userInfo : connectionCache.keySet()) {
            if(connectionCache.get(userInfo) == null ||
                    connectionCache.get(userInfo).isClosed()){
                connectionCache.put(userInfo,
                        DriverManager.getConnection(userInfo.getUrl(), userInfo.getUsername(), userInfo.getPassword()));
            }
        }
        return connectionCache.values();
    }

    /**
     * 该插件监控的服务标记名称,目的是为能够在操作系统中准确定位该插件监控的是哪个具体服务
     * 如该服务运行的端口号等
     * 若不需要指定则可返回null
     *
     * @return
     */
    @Override
    public String agentSignName() {
        return null;
    }

    /**
     * 插件监控的服务正常运行时的內建监控报告
     * 若有些特殊的监控值无法用配置文件进行配置监控,可利用此方法进行硬编码形式进行获取
     * 注:此方法只有在监控对象可用时,才会调用,并加入到监控值报告中,一并上传
     *
     * @return
     */
    @Override
    public Collection<FalconReportObject> inbuiltReportObjectsForValid() throws SQLException, ClassNotFoundException {
        List<FalconReportObject> result = new ArrayList<>();
        String sql = "SELECT\n" +
                "  Upper(F.TABLESPACE_NAME) \"TSNAME\",\n" +
                "  To_char(Round((D.TOT_GROOTTE_MB - F.TOTAL_BYTES) / D.TOT_GROOTTE_MB * 100, 2), '990.99') \"PERCENT\"\n" +
                "FROM (SELECT\n" +
                "        TABLESPACE_NAME,\n" +
                "        Round(Sum(BYTES) / (1024 * 1024), 2) TOTAL_BYTES,\n" +
                "        Round(Max(BYTES) / (1024 * 1024), 2) MAX_BYTES\n" +
                "      FROM SYS.DBA_FREE_SPACE\n" +
                "      GROUP BY TABLESPACE_NAME) F,\n" +
                "  (SELECT\n" +
                "     DD.TABLESPACE_NAME,\n" +
                "     Round(Sum(DD.BYTES) / (1024 * 1024), 2) TOT_GROOTTE_MB\n" +
                "   FROM SYS.DBA_DATA_FILES DD\n" +
                "   GROUP BY DD.TABLESPACE_NAME) D\n" +
                "WHERE D.TABLESPACE_NAME = F.TABLESPACE_NAME";
        for (Connection connection : getConnections()) {
            //创建该连接下的PreparedStatement对象
            PreparedStatement pstmt = connection.prepareStatement(sql);
            //执行查询语句，将数据保存到ResultSet对象中
            ResultSet rs = pstmt.executeQuery();
            //将指针移到下一行，判断rs中是否有数据
            while (rs.next()){
                String tsName = rs.getString(1);
                String percent = rs.getString(2);

                FalconReportObject falconReportObject = new FalconReportObject();
                MetricsCommon.setReportCommonValue(falconReportObject,step());
                falconReportObject.setCounterType(CounterType.GAUGE);
                falconReportObject.setTimestamp(System.currentTimeMillis() / 1000);
                falconReportObject.setMetric(getMetricsName("TSUsedPercent-" + tsName.trim()));
                falconReportObject.setValue(percent.trim());
                falconReportObject.appendTags(MetricsCommon.getTags(agentSignName(),this,serverName(), MetricsType.SQL_IN_BUILD));
                result.add(falconReportObject);
            }
            rs.close();
            pstmt.close();
        }

        return result;
    }

    /**
     * JDBC连接的关闭
     */
    @Override
    public void close() throws SQLException {
        for (Connection connection : connectionCache.values()) {
            if(connection != null){
                connection.close();
            }
        }
    }

    /**
     * 插件初始化操作
     * 该方法将会在插件运行前进行调用
     * @param properties
     * 包含的配置:
     * 1、插件目录绝对路径的(key 为 pluginDir),可利用此属性进行插件自定制资源文件读取
     * 2、插件指定的配置文件的全部配置信息(参见 {@link com.yiji.falcon.agent.plugins.Plugin#configFileName()} 接口项)
     * 3、授权配置项(参见 {@link com.yiji.falcon.agent.plugins.Plugin#authorizationKeyPrefix()} 接口项
     */
    @Override
    public void init(Map<String, String> properties) {
        String authProp = properties.get("Oracle.jdbc.auth");
        if(!StringUtils.isEmpty(authProp)){
            String[] auths = authProp.split("\\^");
            for (String auth : auths) {
                if (!StringUtils.isEmpty(auth)){
                    String url = auth.substring(auth.indexOf("url=") + 4,auth.indexOf("user=") - 1);
                    String user = auth.substring(auth.indexOf("user=") + 5,auth.indexOf("pswd=") - 1);
                    String pswd = auth.substring(auth.indexOf("pswd=") + 5);
                    JDBCUserInfo userInfo = new JDBCUserInfo(url,user,pswd);
                    userInfoList.add(userInfo);
                }
            }
        }

        step = Integer.parseInt(properties.get("step"));
        pluginActivateType = PluginActivateType.valueOf(properties.get("pluginActivateType"));
    }

    /**
     * 该插件监控的服务名
     * 该服务名会上报到Falcon监控值的tag(service)中,可用于区分监控值服务
     *
     * @return
     */
    @Override
    public String serverName() {
        return "oracle";
    }

    /**
     * 监控值的获取和上报周期(秒)
     *
     * @return
     */
    @Override
    public int step() {
        return step;
    }

    /**
     * 插件运行方式
     *
     * @return
     */
    @Override
    public PluginActivateType activateType() {
        return pluginActivateType;
    }
}