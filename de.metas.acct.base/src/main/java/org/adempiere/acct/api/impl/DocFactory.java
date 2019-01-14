package org.adempiere.acct.api.impl;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2015 metas GmbH
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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.adempiere.acct.api.IDocFactory;
import org.adempiere.acct.api.IDocMetaInfo;
import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.persistence.TableModelLoader;
import org.adempiere.ad.table.api.IADTableDAO;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.acct.Doc;
import org.compiere.acct.IDocBuilder;
import org.compiere.acct.PostingExecutionException;
import org.compiere.model.I_AD_Column;
import org.compiere.model.I_AD_Table;
import org.compiere.model.PO;
import org.compiere.util.Env;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import de.metas.acct.api.AcctSchema;
import de.metas.logging.LogManager;
import de.metas.util.Check;
import de.metas.util.Services;
import lombok.NonNull;
import lombok.ToString;

public class DocFactory implements IDocFactory
{
	/** Static Log */
	private final transient Logger logger = LogManager.getLogger(getClass());

	/** Map of AD_Table_ID to {@link IDocMetaInfo} */
	private transient ImmutableMap<Integer, IDocMetaInfo> _tableId2docMetaInfo = null;

	/** {@link Doc} instance builder */
	@ToString
	private final class DocBuilder implements IDocBuilder
	{
		private Object documentModel;
		private ImmutableList<AcctSchema> acctSchemas;
		private IDocMetaInfo docMetaInfo;

		@Override
		public Doc<?> build()
		{
			Check.assumeNotEmpty(acctSchemas, "acctSchemas is not empty");

			try
			{
				final IDocMetaInfo docMetaInfo = getDocMetaInfo();
				// Get the accountable document instance
				final Doc<?> doc = docMetaInfo.getDocConstructor().newInstance(this);

				// Return it
				return doc;
			}
			catch (final Exception e)
			{
				throw PostingExecutionException.wrapIfNeeded(e);
			}

		}

		@Override
		public ImmutableList<AcctSchema> getAcctSchemas()
		{
			return acctSchemas;
		}

		@Override
		public DocBuilder setAcctSchemas(final List<AcctSchema> acctSchemas)
		{
			this.acctSchemas = ImmutableList.copyOf(acctSchemas);
			return this;
		}

		@Override
		public Object getDocumentModel()
		{
			return documentModel;
		}

		@Override
		public DocBuilder setDocumentModel(final Object documentModel)
		{
			Check.assumeNotNull(documentModel, "documentModel not null");
			this.documentModel = documentModel;
			return this;
		}

		public DocBuilder setDocMetaInfo(final IDocMetaInfo docMetaInfo)
		{
			this.docMetaInfo = docMetaInfo;
			return this;
		}

		public IDocMetaInfo getDocMetaInfo()
		{
			Check.assumeNotNull(docMetaInfo, "docMetaInfo not null");
			return docMetaInfo;
		}

		@Override
		public IDocFactory getDocFactory()
		{
			return DocFactory.this;
		}
	}

	public DocFactory()
	{
		super();
	}

	@Override
	public Doc<?> getOrNull(final List<AcctSchema> acctSchemas, @NonNull final TableRecordReference documentRef)
	{
		Check.assumeNotEmpty(acctSchemas, "acctSchemas is not empty");

		final int adTableId = documentRef.getAD_Table_ID();
		final int recordId = documentRef.getRecord_ID();

		final IDocMetaInfo docMetaInfo = getDocMetaInfoOrNull(adTableId);
		if (docMetaInfo == null)
		{
			// metas: tsa: use info instead of severe because this issue can happen on automatic posting too
			logger.info("Not found AD_Table_ID={}", adTableId);
			return null;
		}

		final String tableName = docMetaInfo.getTableName();
		final PO documentModel = TableModelLoader.instance.getPO(Env.getCtx(), tableName, recordId, ITrx.TRXNAME_ThreadInherited);
		if (documentModel == null)
		{
			logger.error("Not Found: {}_ID={} (Processed=Y)", tableName, recordId);
			return null;
		}

		return new DocBuilder()
				.setDocMetaInfo(docMetaInfo)
				.setDocumentModel(documentModel)
				.setAcctSchemas(acctSchemas)
				.build();
	}

	private final synchronized ImmutableMap<Integer, IDocMetaInfo> getDocMetaInfoMap()
	{
		ImmutableMap<Integer, IDocMetaInfo> tableId2docMetaInfo = _tableId2docMetaInfo;
		if (tableId2docMetaInfo == null)
		{
			tableId2docMetaInfo = _tableId2docMetaInfo = loadDocMetaInfo();
		}
		return tableId2docMetaInfo;
	}

	@Override
	public List<IDocMetaInfo> getDocMetaInfoList()
	{
		return new ArrayList<>(getDocMetaInfoMap().values());
	}

	@Override
	public Set<Integer> getDocTableIds()
	{
		return getDocMetaInfoMap().keySet();
	}

	/** @return accountable document meta-info for given AD_Table_ID */
	private final IDocMetaInfo getDocMetaInfoOrNull(final int adTableId)
	{
		return getDocMetaInfoMap().get(adTableId);
	}

	/** Retries all accountable document meta-info from system */
	private final ImmutableMap<Integer, IDocMetaInfo> loadDocMetaInfo()
	{
		//
		// Finds all AD_Table_IDs for tables which are not Views and have a column called "Posted"
		final List<Integer> tableIds = Services.get(IQueryBL.class)
				.createQueryBuilderOutOfTrx(I_AD_Column.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_AD_Column.COLUMN_ColumnName, "Posted")
				.andCollect(I_AD_Column.COLUMN_AD_Table_ID)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_AD_Table.COLUMNNAME_IsView, false) // skip tables which are views
				.create()
				.listIds();

		final ImmutableMap.Builder<Integer, IDocMetaInfo> tableId2docMetaInfo = ImmutableMap.builder();
		for (final int adTableId : tableIds)
		{
			final IDocMetaInfo docMetaData = createDocMetaInfoOrNull(adTableId);
			if (docMetaData == null)
			{
				continue;
			}

			tableId2docMetaInfo.put(adTableId, docMetaData);
		}

		return tableId2docMetaInfo.build();
	}

	/**
	 * Creates accountable document meta-info for given AD_Table_ID
	 * 
	 * @param adTableId
	 * @return document meta-info or <code>null</code> if document is not accountable
	 */
	private IDocMetaInfo createDocMetaInfoOrNull(final int adTableId)
	{
		final String tableName = Services.get(IADTableDAO.class).retrieveTableName(adTableId);

		//
		// Build classname based on tableName.
		// Classname of the Doc class follows this convention:
		// * if the prefix (letters before the first underscore _) is 1 character, then the class is Doc_TableWithoutPrefixWithoutUnderscores
		// * otherwise Doc_WholeTableWithoutUnderscores
		final String packageName = "org.compiere.acct";
		final String className;
		final int firstUnderscore = tableName.indexOf("_");
		if (firstUnderscore == 1)
		{
			className = packageName + ".Doc_" + tableName.substring(2).replaceAll("_", "");
		}
		else
		{
			className = packageName + ".Doc_" + tableName.replaceAll("_", "");
		}

		//
		// Load class and constructor
		try
		{
			// Get the class loader to be used
			ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			if (classLoader == null)
			{
				classLoader = getClass().getClassLoader();
			}

			@SuppressWarnings("unchecked")
			final Class<? extends Doc<?>> docClass = (Class<? extends Doc<?>>)classLoader.loadClass(className);
			final Constructor<? extends Doc<?>> docConstructor = docClass.getConstructor(new Class[] { IDocBuilder.class });
			docConstructor.setAccessible(true);

			final DocMetaInfo docMetaInfo = new DocMetaInfo(adTableId, tableName, docClass, docConstructor);
			return docMetaInfo;
		}
		catch (final ClassNotFoundException e)
		{
			// NOTE: it could be that some Doc classes are missing (e.g. Doc_HRProcess),
			// and we don't want to pollute the log.
			final AdempiereException ex = new AdempiereException("No Doc class found for " + className, e);
			logger.info(ex.getLocalizedMessage(), ex);
		}
		catch (final Exception e)
		{
			final AdempiereException ex = new AdempiereException("Error while loading Doc class found for " + className, e);
			logger.warn(ex.getLocalizedMessage(), ex);
		}

		// No meta-info found
		return null;
	}
}
