/**
 * 
 */
package de.metas.fresh.product.process;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import org.apache.poi.ss.usermodel.Font;
import org.compiere.Adempiere;
import org.compiere.Adempiere.RunMode;
import org.compiere.util.Ini;

import de.metas.adempiere.form.IClientUI;
import de.metas.data.export.api.IExportDataSource;
import de.metas.data.export.api.IExporter;
import de.metas.data.export.api.IExporterFactory;
import de.metas.data.export.api.impl.CSVWriter;
import de.metas.data.export.api.impl.JdbcExporterBuilder;
import de.metas.i18n.IMsgBL;
import de.metas.process.JavaProcess;
import de.metas.util.Services;
import de.metas.util.time.SystemTime;

/*
 * #%L
 * de.metas.fresh.base
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/**
 * @author metas-dev <dev@metasfresh.com>
 *
 */
public class ExportProductSpecifications extends JavaProcess
{

	private final static String tableName = "\"de.metas.fresh\".product_specifications_v";
	private final IMsgBL msgBL = Services.get(IMsgBL.class);

	@Override
	protected String doIt() throws Exception
	{
		final IExportDataSource dataSource = createDataSource();
		final Properties config = new Properties();
		config.setProperty(CSVWriter.CONFIG_Encoding, "Cp1252");
		final IExporter csvExporter = Services.get(IExporterFactory.class).createExporter(IExporterFactory.MIMETYPE_CSV, dataSource, config);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		csvExporter.export(out);

		final List<List<Object>> data = excelExporterService.getDataFromSQL(getSql());
		final File tempFile = ArrayExcelExporter.builder()
				.ctx(getCtx())
				.data(data)
				.columnHeaders(getColumnHeaders())
				.build()
				.setFontCharset(Font.ANSI_CHARSET)
				.exportToTempFile();

		final boolean backEndOrSwing = Ini.getRunMode() == RunMode.BACKEND || Ini.isClient();

		if (backEndOrSwing)
		{
			Services.get(IClientUI.class).download(out.toByteArray(), // data
					"text/csv", // content type
					buildFilename()); // filename
		}
		else
		{
			getResult().setReportData(
					out.toByteArray(), // data
					buildFilename(), // filename
					"text/csv"); // content type
		}

		return MSG_OK;
	}

	public IExportDataSource createDataSource()
	{
		final JdbcExporterBuilder builder = new JdbcExporterBuilder(tableName);

		builder.addWhereClause("1=1", new Object[] {});
		builder.addOrderBy("productValue");

		builder.addField(msgBL.translate(getCtx(), "ProductName"), "productName");
		builder.addField(msgBL.translate(getCtx(), "CustomerLabelName"), "CustomerLabelName");
		builder.addField(msgBL.translate(getCtx(), "Additional_produktinfos"), "additional_produktinfos");
		builder.addField(msgBL.translate(getCtx(), "ProductValue"), "productValue");
		builder.addField(msgBL.translate(getCtx(), "UPC"), "UPC");
		builder.addField(msgBL.translate(getCtx(), "NetWeight"), "weight");
		builder.addField(msgBL.translate(getCtx(), "Country"), "country");
		builder.addField(msgBL.translate(getCtx(), "IsPackagingMaterial"), "piName");
		builder.addField(msgBL.translate(getCtx(), "NumberOfEvents"), "piQty");
		builder.addField(msgBL.translate(getCtx(), "ShelfLifeDays"), "guaranteedaysmin");
		builder.addField(msgBL.translate(getCtx(), "Warehouse_temperature"), "warehouse_temperature");
		builder.addField(msgBL.translate(getCtx(), "ProductDescription"), "productDecription");
		builder.addField(msgBL.translate(getCtx(), "M_BOMProduct_ID"), "componentName");
		builder.addField(msgBL.translate(getCtx(), "Ingredients"), "componentIngredients");
		builder.addField(msgBL.translate(getCtx(), "QtyBatch"), "qtybatch");
		builder.addField(msgBL.translate(getCtx(), "Allergen"), "allergen");
		builder.addField(msgBL.translate(getCtx(), "M_Product_Nutrition_ID"), "nutritionName");
		builder.addField(msgBL.translate(getCtx(), "NutritionQty"), "nutritionqty");

		return builder.createDataSource();
	}

	private String buildFilename()
	{
		final StringBuilder filename = new StringBuilder("ProductSpecificationd");
		filename.append("_");

		final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
		filename.append(dateFormatter.format(SystemTime.asLocalDate()));

		filename.append(".").append("csv");

		return filename.toString();
	}

}
