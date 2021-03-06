/*
 * <<
 *  Davinci
 *  ==
 *  Copyright (C) 2016 - 2018 EDP
 *  ==
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  >>
 *
 */

package edp.davinci.core.utils;

import com.alibaba.druid.util.StringUtils;
import com.alibaba.fastjson.JSONObject;
import edp.core.enums.SqlTypeEnum;
import edp.core.exception.ServerException;
import edp.core.model.QueryColumn;
import edp.core.utils.FileUtils;
import edp.core.utils.SqlUtils;
import edp.davinci.core.common.Constants;
import edp.davinci.core.enums.FileTypeEnum;
import edp.davinci.core.enums.SqlColumnEnum;
import edp.davinci.core.model.DataUploadEntity;
import edp.davinci.core.model.ExcelHeader;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.web.multipart.MultipartFile;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.*;

public class ExcelUtils {


    /**
     * 解析上传Excel
     *
     * @param excelFile
     * @return
     */
    public static DataUploadEntity parseExcelWithFirstAsHeader(MultipartFile excelFile) {

        if (null == excelFile) {
            throw new ServerException("Invalid excel file");
        }

        if (!FileUtils.isExcel(excelFile)) {
            throw new ServerException("Invalid excel file");
        }

        DataUploadEntity dataUploadEntity = null;

        Workbook workbook = null;

        try {
            workbook = getReadWorkbook(excelFile);

            //只读取第一个sheet页
            Sheet sheet = workbook.getSheetAt(0);

            //前两行表示列名和类型
            if (sheet.getLastRowNum() < 1) {
                throw new ServerException("empty excel");
            }
            //列
            Row headerRow = sheet.getRow(0);
            Row typeRow = sheet.getRow(1);

            List<Map<String, Object>> values = null;
            Set<QueryColumn> headers = new HashSet<>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                try {
                    headers.add(new QueryColumn(headerRow.getCell(i).getStringCellValue(),
                            SqlUtils.formatSqlType(typeRow.getCell(i).getStringCellValue())));
                } catch (Exception e) {
                    e.printStackTrace();
                    if (e instanceof NullPointerException) {
                        throw new ServerException("Unknown Type");
                    }
                    throw new ServerException(e.getMessage());
                }
            }

            values = new ArrayList<>();
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                Map<String, Object> item = new HashMap<>();
                for (int j = 0; j < headerRow.getLastCellNum(); j++) {
                    item.put(headerRow.getCell(j).getStringCellValue(),
                            SqlColumnEnum.formatValue(typeRow.getCell(j).getStringCellValue(), row.getCell(j).getStringCellValue()));
                }
                values.add(item);
            }

            dataUploadEntity = new DataUploadEntity();
            dataUploadEntity.setHeaders(headers);
            dataUploadEntity.setValues(values);

        } catch (ServerException e) {
            e.printStackTrace();
            throw new ServerException(e.getMessage());
        }

        return dataUploadEntity;
    }

    private static Workbook getReadWorkbook(MultipartFile excelFile) throws ServerException {
        InputStream inputStream = null;
        try {

            String originalFilename = excelFile.getOriginalFilename();
            inputStream = excelFile.getInputStream();
            if (originalFilename.toLowerCase().endsWith(FileTypeEnum.XLSX.getFormat())) {
                return new XSSFWorkbook(inputStream);
            } else if (originalFilename.toLowerCase().endsWith(FileTypeEnum.XLS.getFormat())) {
                return new HSSFWorkbook(inputStream);
            } else {
                throw new ServerException("Invalid excel file");
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new ServerException(e.getMessage());
        } finally {
            try {
                if (null != inputStream) {
                    inputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new ServerException(e.getMessage());
            }
        }
    }


    /**
     * 写入数据到excel sheet页
     *
     * @param sheet
     * @param columns
     * @param dataList
     * @param workbook
     */
    public static void writeSheet(XSSFSheet sheet,
                                  List<QueryColumn> columns,
                                  List<Map<String, Object>> dataList,
                                  XSSFWorkbook workbook,
                                  boolean containType,
                                  String json) {


        XSSFRow row = null;

        XSSFCellStyle cellStyle = workbook.createCellStyle();
        XSSFCellStyle headerCellStyle = workbook.createCellStyle();


        XSSFDataFormat format = workbook.createDataFormat();
        cellStyle.setDataFormat(format.getFormat("@"));
        headerCellStyle.setDataFormat(format.getFormat("@"));

        //表头粗体居中
        XSSFFont font = workbook.createFont();
        font.setFontName("黑体");
        font.setBold(true);
        headerCellStyle.setFont(font);
        headerCellStyle.setAlignment(HorizontalAlignment.CENTER);
        headerCellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        boolean isTable = isTable(json);

        ScriptEngine engine = null;
        List<ExcelHeader> excelHeaders = null;
        if (isTable) {
            try {
                engine = getScriptEngine();
                excelHeaders = formatHeader(engine, json);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        int rownum = 0;

        //header
        if (isTable && null != excelHeaders && excelHeaders.size() > 0) {

            int colnum = 0;

            List<QueryColumn> columnList = new ArrayList<>();
            for (ExcelHeader excelHeader : excelHeaders) {
                if (excelHeader.getRow() + 1 > rownum) {
                    rownum = excelHeader.getRow();
                }
                if (excelHeader.getCol() + 1 > colnum) {
                    colnum = excelHeader.getCol();
                }

                //调整数据渲染顺序
                for (QueryColumn queryColumn : columns) {
                    if (queryColumn.getName().equals(excelHeader.getKey())) {
                        columnList.add(queryColumn);
                    }
                }
            }

            if (null != columnList && columnList.size() > 0) {
                columns = columnList;
            }

            //画出表头
            for (int i = 0; i < rownum + 1; i++) {
                XSSFRow headerRow = sheet.createRow(i);
                for (int j = 0; j <= colnum; j++) {
                    headerRow.createCell(j);
                }
            }

            for (ExcelHeader excelHeader : excelHeaders) {

                //合并单元格
                if (excelHeader.isMerged() && null != excelHeader.getRange() && excelHeader.getRange().length == 4) {
                    int[] range = excelHeader.getRange();
                    if (!(range[0] == range[1] && range[2] == range[3])) {
                        CellRangeAddress cellRangeAddress = new CellRangeAddress(range[0], range[1], range[2], range[3]);
                        sheet.addMergedRegion(cellRangeAddress);
                    }
                }
                XSSFCell cell = sheet.getRow(excelHeader.getRow()).getCell(excelHeader.getCol());
                cell.setCellStyle(headerCellStyle);
                cell.setCellValue(StringUtils.isEmpty(excelHeader.getAlias()) ? excelHeader.getKey() : excelHeader.getAlias());
            }

        } else {
            row = sheet.createRow(rownum);
            for (int i = 0; i < columns.size(); i++) {
                XSSFCell cell = row.createCell(i);
                cell.setCellStyle(headerCellStyle);
                cell.setCellValue(columns.get(i).getName());
            }
        }

        //type
        if (containType) {
            rownum++;
            row = sheet.createRow(rownum);
            for (int i = 0; i < columns.size(); i++) {
                String type = columns.get(i).getType();
                if (isTable) {
                    type = SqlTypeEnum.VARCHAR.getName();
                }
                row.createCell(i).setCellValue(type);
            }
        }

        if (isTable(json)) {
            long l2 = System.currentTimeMillis();
            dataList = formatValue(engine, dataList, json);
            long l3 = System.currentTimeMillis();
            System.out.println("data format: >>>>>" + (l3 - l2));
        }

        //data
        for (int i = 0; i < dataList.size(); i++) {
            rownum++;
            if (containType) {
                rownum += 1;
            }
            row = sheet.createRow(rownum);
            Map<String, Object> map = dataList.get(i);

            for (int j = 0; j < columns.size(); j++) {
                Object obj = map.get(columns.get(j).getName());
                String v = "";
                if (null != obj) {
                    if (obj instanceof Double || obj instanceof Float) {
                        DecimalFormat decimalFormat = new DecimalFormat("#,###.####");
                        v = decimalFormat.format(obj);
                    } else {
                        v = obj.toString();
                    }
                }
                XSSFCell cell = row.createCell(j);
                cell.setCellValue(v);
                cell.setCellStyle(cellStyle);
            }
        }


        sheet.setDefaultRowHeight((short) (16.5 * 20));
        for (int i = 0; i < columns.size(); i++) {
            sheet.autoSizeColumn(i);
        }
    }


    /**
     * format cell value
     *
     * @param engine
     * @param list
     * @param json
     * @return
     */
    private static List<Map<String, Object>> formatValue(ScriptEngine engine, List<Map<String, Object>> list, String json) {
        try {
            Invocable invocable = (Invocable) engine;
            Object obj = invocable.invokeFunction("getFormattedDataRows", json, list);

            if (obj instanceof ScriptObjectMirror) {
                ScriptObjectMirror som = (ScriptObjectMirror) obj;
                if (som.isArray()) {
                    final List<Map<String, Object>> convertList = new ArrayList<>();
                    Collection<Object> values = som.values();
                    values.forEach(v -> {
                        Map<String, Object> map = new HashMap<>();
                        ScriptObjectMirror vsom = (ScriptObjectMirror) v;
                        for (String key : vsom.keySet()) {
                            map.put(key, vsom.get(key));
                        }
                        convertList.add(map);
                    });
                    return convertList;
                }
            }

        } catch (ScriptException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }

        return list;
    }

    private static List<ExcelHeader> formatHeader(ScriptEngine engine, String json) {
        try {
            Invocable invocable = (Invocable) engine;
            Object obj = invocable.invokeFunction("getFieldsHeader", json);


            if (obj instanceof ScriptObjectMirror) {
                ScriptObjectMirror som = (ScriptObjectMirror) obj;
                if (som.isArray()) {
                    final List<ExcelHeader> excelHeaders = new ArrayList<>();
                    Collection<Object> values = som.values();
                    values.forEach(v -> {
                        ExcelHeader header = new ExcelHeader();
                        ScriptObjectMirror vsom = (ScriptObjectMirror) v;
                        for (String key : vsom.keySet()) {
                            if (!StringUtils.isEmpty(key)) {
                                String setter = "set" + String.valueOf(key.charAt(0)).toUpperCase() + key.substring(1);
                                Object o = vsom.get(key);
                                Class clazz = o.getClass();

                                try {
                                    if (o instanceof ScriptObjectMirror) {
                                        ScriptObjectMirror mirror = (ScriptObjectMirror) o;
                                        if ("range".equals(key)) {
                                            if (mirror.isArray()) {
                                                int[] array = new int[4];
                                                for (int i = 0; i < 4; i++) {
                                                    array[i] = Integer.parseInt(mirror.get(i + "").toString());
                                                }
                                                header.setRange(array);
                                            }
                                        } else if ("style".equals(key)) {
                                            if (mirror.isArray()) {
                                                List<String> list = new ArrayList<>();
                                                for (int i = 0; i < 4; i++) {
                                                    list.add(mirror.get(i + "").toString());
                                                }
                                                header.setStyle(list);
                                            }
                                        }

                                    } else {
                                        Method method = header.getClass().getMethod(setter, clazz);
                                        method.invoke(header, vsom.get(key));
                                    }
                                } catch (NoSuchMethodException e) {
                                    e.printStackTrace();
                                } catch (IllegalAccessException e) {
                                    e.printStackTrace();
                                } catch (InvocationTargetException e) {
                                    e.printStackTrace();
                                } finally {
                                    continue;
                                }
                            }
                        }
                        excelHeaders.add(header);
                    });
                    return excelHeaders;
                }
            }

        } catch (ScriptException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
        return null;
    }


    private static ScriptEngine getScriptEngine() throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        ClassLoader classLoader = ExcelUtils.class.getClassLoader();
        engine.eval(new InputStreamReader(classLoader.getResourceAsStream(Constants.TABLE_FORMAT_JS)));
        return engine;
    }


    private static boolean isTable(String json) {
        if (!StringUtils.isEmpty(json)) {
            try {
                JSONObject jsonObject = JSONObject.parseObject(json);
                if (null != jsonObject) {
                    if (jsonObject.containsKey("selectedChart") && jsonObject.containsKey("mode")) {
                        Integer selectedChart = jsonObject.getInteger("selectedChart");
                        String mode = jsonObject.getString("mode");
                        if (selectedChart.equals(1) && mode.equals("chart")) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
