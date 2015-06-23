package com.ctrip.infosec.rule.convert;

import com.ctrip.infosec.configs.event.*;
import com.ctrip.infosec.configs.event.enums.DataUnitType;
import com.ctrip.infosec.configs.event.enums.PersistColumnSourceType;
import com.ctrip.infosec.configs.event.enums.PersistOperationType;
import com.ctrip.infosec.rule.convert.config.RiskFactPersistConfigHolder;
import com.ctrip.infosec.rule.convert.internal.DataUnit;
import com.ctrip.infosec.rule.convert.internal.InternalRiskFact;
import com.ctrip.infosec.rule.convert.persist.*;
import com.google.common.collect.Maps;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Created by yxjiang on 2015/6/15.
 */
public class RiskFactPersistStrategy {
    public static RiskFactPersistManager preparePersistance(InternalRiskFact fact) {
        RiskFactPersistManager persistManager = new RiskFactPersistManager();
        InternalRiskFactPersistConfig config = RiskFactPersistConfigHolder.localPersistConfigs.get(fact.getEventPoint());
        persistManager.setOperationChain(buildDbOperationChain(fact, config));
        return persistManager;
    }

    private static DbOperationChain buildDbOperationChain(InternalRiskFact fact, InternalRiskFactPersistConfig config) {
        DbOperationChain firstOne = null;
        DbOperationChain last = null;
        List<RdbmsTableOperationConfig> opConfigs = config.getOps();
        for (RdbmsTableOperationConfig operationConfig : opConfigs) {
            DataUnitMetadata meta = getMetadata(operationConfig.getDataUnitMetaId());
            DbOperationChain chain = buildDbOperationChain(findCorrespondingDataUnit(fact, meta.getName()), operationConfig, meta);
            if (chain != null) {
                if (firstOne == null) {
                    firstOne = chain;
                }
                if (last != null) {
                    last.setNextOperationChain(chain);
                }
                last = chain;
            }
        }
        return firstOne;
    }

    private static DbOperationChain buildDbOperationChain(DataUnit dataUnit, RdbmsTableOperationConfig config, DataUnitMetadata meta) {
        if (dataUnit == null) {
            return null;
        }
        DataUnitType type = DataUnitType.getByCode(dataUnit.getDefinition().getType());
        switch (type) {
            case SINGLE:
                return buildDbOperationChain((Map<String, Object>) dataUnit.getData(), config, meta);
            case LIST:
                return buildDbOperationChain((List<Map<String, Object>>) dataUnit.getData(), config, meta);
        }
        return null;
    }

    private static DbOperationChain buildDbOperationChain(List<Map<String, Object>> dataList, RdbmsTableOperationConfig config, DataUnitMetadata meta) {
        DbOperationChain firstOne = null;
        DbOperationChain last = null;
        if (CollectionUtils.isNotEmpty(dataList)) {
            for (Map<String, Object> data : dataList) {
                DbOperationChain chain = buildDbOperationChain(data, config, meta);
                if (chain != null) {
                    if (firstOne == null) {
                        firstOne = chain;
                    }
                    if (last != null) {
                        last.setNextOperationChain(chain);
                    }
                    last = chain;
                }
            }
        }
        return firstOne;
    }

    private static DbOperationChain buildDbOperationChain(Map<String, Object> data, RdbmsTableOperationConfig config, DataUnitMetadata meta) {
        // 简单类型，对应一个落地操作
        Map<String, Object> simpleFieldMap = extractFieldData(data, meta);
        PersistOperationType operationType = PersistOperationType.getByCode(config.getOpType());
        DbOperationChain chain = null;
        if (operationType == PersistOperationType.INSERT) {
            RdbmsInsert insert = new RdbmsInsert();
            insert.setChannel(config.getChannel());
            insert.setTable(config.getTableName());
            insert.setColumnPropertiesMap(generateColumnProperties(simpleFieldMap, config, meta));
            chain = new DbOperationChain(insert);
        }
        Map<String, Object> complexFieldMap = extractFieldObject(data, meta);
        if (MapUtils.isNotEmpty(complexFieldMap)){
            for (Map.Entry<String, Object> entry : complexFieldMap.entrySet()) {

            }
            // TODO
        }
        return chain;
    }

    private static Map<String, PersistColumnProperties> generateColumnProperties(Map<String, Object> simpleFieldMap, RdbmsTableOperationConfig config, DataUnitMetadata meta) {
        List<RdbmsTableColumnConfig> columnConfigs = config.getColumns();
        Map<String, PersistColumnProperties> rt = Maps.newHashMap();
        for (RdbmsTableColumnConfig columnConfig : columnConfigs) {
            PersistColumnSourceType sourceType = PersistColumnSourceType.getByCode(columnConfig.getSourceType());
            PersistColumnProperties props = new PersistColumnProperties();
            props.setExpression(columnConfig.getSource());
            props.setPersistColumnSourceType(sourceType);
            if (sourceType == PersistColumnSourceType.DATA_UNIT) {
                String metaColumnName = columnConfig.getSource();
                props.setValue(simpleFieldMap.get(metaColumnName));
                props.setColumnType(DataUnitColumnType.getByIndex(meta.getColumn(metaColumnName).getColumnType()));
            }
            rt.put(columnConfig.getName(), props);
        }
        return rt;
    }

    /**
     * 获取data中类型是简单类型的数据
     *
     * @param data
     * @param meta
     * @return
     */
    private static Map<String, Object> extractFieldData(Map<String, Object> data, DataUnitMetadata meta) {
        Map<String, Object> rt = null;
        List<DataUnitColumn> columns = meta.getColumns();
        if (CollectionUtils.isNotEmpty(columns)) {
            rt = Maps.newHashMap();
            for (DataUnitColumn column : columns) {
                String name = column.getName();
                DataUnitColumnType colType = DataUnitColumnType.getByIndex(column.getColumnType());
                if (colType != DataUnitColumnType.List && colType != DataUnitColumnType.Object) {
                    Object val = data.get(name);
                    if (val != null) {
                        rt.put(name, val);
                    }
                }
            }
        }
        return rt;
    }

    /**
     * 获取data中类型是LIST或OBJECT的数据
     *
     * @param data
     * @param meta
     * @return
     */
    private static Map<String, Object> extractFieldObject(Map<String, Object> data, DataUnitMetadata meta) {
        Map<String, Object> rt = null;
        List<DataUnitColumn> columns = meta.getColumns();
        if (CollectionUtils.isNotEmpty(columns)) {
            rt = Maps.newHashMap();
            for (DataUnitColumn column : columns) {
                String name = column.getName();
                DataUnitColumnType colType = DataUnitColumnType.getByIndex(column.getColumnType());
                if (colType == DataUnitColumnType.List || colType == DataUnitColumnType.Object) {
                    Object val = data.get(name);
                    if (val != null) {
                        rt.put(name, val);
                    }
                }
            }
            if (rt.size() == 0) {
                rt = null;
            }
        }
        return rt;
    }

    /**
     * 获取InternalRiskFact中metadata name对应的数据单元
     *
     * @param fact
     * @param metaName
     * @return
     */
    private static DataUnit findCorrespondingDataUnit(InternalRiskFact fact, String metaName) {
        if (CollectionUtils.isNotEmpty(fact.getDataUnits())) {
            for (DataUnit dataUnit : fact.getDataUnits()) {
                if (StringUtils.equals(dataUnit.getMetadata().getName(), metaName)) {
                    return dataUnit;
                }
            }
        }
        return null;
    }

    private static DataUnitMetadata getMetadata(String dataUnitMetaId) {
        return RiskFactPersistConfigHolder.localDataUnitMetadatas.get(dataUnitMetaId);
    }
}