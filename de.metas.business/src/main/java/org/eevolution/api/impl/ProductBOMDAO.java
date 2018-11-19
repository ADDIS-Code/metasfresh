package org.eevolution.api.impl;

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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import org.adempiere.ad.dao.ICompositeQueryFilter;
import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryBuilder;
import org.adempiere.ad.dao.IQueryFilter;
import org.adempiere.ad.dao.ISqlQueryFilter;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.proxy.Cached;
import org.compiere.model.IQuery;
import org.compiere.model.I_M_Product;
import org.eevolution.api.IProductBOMBL;
import org.eevolution.api.IProductBOMDAO;
import org.eevolution.model.I_PP_Product_BOM;
import org.eevolution.model.I_PP_Product_BOMLine;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import de.metas.cache.annotation.CacheCtx;
import de.metas.cache.annotation.CacheTrx;
import de.metas.product.IProductDAO;
import de.metas.product.ProductId;
import de.metas.util.Check;
import de.metas.util.Services;
import lombok.NonNull;

public class ProductBOMDAO implements IProductBOMDAO
{
	@Override
	public List<I_PP_Product_BOMLine> retrieveLines(final I_PP_Product_BOM productBOM)
	{
		final Properties ctx = InterfaceWrapperHelper.getCtx(productBOM);
		final String trxName = InterfaceWrapperHelper.getTrxName(productBOM);
		final int productBOM_ID = productBOM.getPP_Product_BOM_ID();
		return retrieveLines(ctx, productBOM_ID, trxName);
	}

	@Cached(cacheName = I_PP_Product_BOMLine.Table_Name + "#by#" + I_PP_Product_BOMLine.COLUMNNAME_PP_Product_BOM_ID)
	/* package */ List<I_PP_Product_BOMLine> retrieveLines(
			@CacheCtx final Properties ctx,
			final int productBOM_ID,
			@CacheTrx final String trxName)
	{
		final IQueryBuilder<I_PP_Product_BOMLine> queryBuilder = Services.get(IQueryBL.class)
				.createQueryBuilder(I_PP_Product_BOMLine.class, ctx, trxName);

		final ICompositeQueryFilter<I_PP_Product_BOMLine> filters = queryBuilder.getCompositeFilter();
		filters.addOnlyActiveRecordsFilter();
		filters.addEqualsFilter(I_PP_Product_BOMLine.COLUMNNAME_PP_Product_BOM_ID, productBOM_ID);

		queryBuilder.orderBy()
				.addColumn(I_PP_Product_BOMLine.COLUMNNAME_Line)
				.addColumn(I_PP_Product_BOMLine.COLUMNNAME_PP_Product_BOMLine_ID);

		return queryBuilder.create().list();
	}	// getLines

	@Override
	public List<I_PP_Product_BOMLine> retrieveLines(final I_PP_Product_BOM productBOM, final Date date)
	{
		final List<I_PP_Product_BOMLine> linesAll = retrieveLines(productBOM);
		final List<I_PP_Product_BOMLine> linesValid = new ArrayList<>(linesAll.size()); // Selected BOM Lines Only
		for (final I_PP_Product_BOMLine bomLine : linesAll)
		{
			if (!Services.get(IProductBOMBL.class).isValidFromTo(bomLine, date))
			{
				continue;
			}
			linesValid.add(bomLine);
		}
		//
		return linesValid;
	}

	@Override
	public int retrieveDefaultBOMId(final I_M_Product product)
	{
		final I_PP_Product_BOM bom = retrieveDefaultBOM(product);
		if (bom == null)
		{
			return -1;
		}
		return bom.getPP_Product_BOM_ID();
	}

	@Override
	public int getDefaultProductBOMIdByProductId(@NonNull final ProductId productId)
	{
		final I_M_Product product = Services.get(IProductDAO.class).getById(productId);
		return retrieveDefaultBOMId(product);
	}

	@Override
	public I_PP_Product_BOM retrieveDefaultBOM(final I_M_Product product)
	{
		final Properties ctx = InterfaceWrapperHelper.getCtx(product);
		final String trxName = InterfaceWrapperHelper.getTrxName(product);
		final int productId = product.getM_Product_ID();
		final String productValue = product.getValue();

		return retrieveDefaultBOM(ctx, productId, productValue, trxName);
	}

	@Cached(cacheName = I_PP_Product_BOM.Table_Name + "#by#IsDefault")
	/* package */ I_PP_Product_BOM retrieveDefaultBOM(
			@CacheCtx final Properties ctx,
			final int productId,
			final String productValue,
			@CacheTrx final String trxName)
	{
		final IQueryBuilder<I_PP_Product_BOM> queryBuilder = Services.get(IQueryBL.class)
				.createQueryBuilder(I_PP_Product_BOM.class, ctx, trxName);

		final ICompositeQueryFilter<I_PP_Product_BOM> filters = queryBuilder.getCompositeFilter();
		filters.addEqualsFilter(I_PP_Product_BOM.COLUMNNAME_M_Product_ID, productId);
		filters.addEqualsFilter(I_PP_Product_BOM.COLUMNNAME_Value, productValue);
		filters.addOnlyActiveRecordsFilter();
		filters.addOnlyContextClient(ctx);

		return queryBuilder
				.create()
				.firstOnly(I_PP_Product_BOM.class);
	}

	@Override
	@Cached(cacheName = I_PP_Product_BOM.Table_Name + "#by#" + I_PP_Product_BOM.COLUMNNAME_PP_Product_BOM_ID)
	public I_PP_Product_BOM retrieveBOMById(@CacheCtx final Properties ctx, final int productBomId)
	{
		if (productBomId <= 0)
		{
			return null;
		}
		return InterfaceWrapperHelper.create(ctx, productBomId, I_PP_Product_BOM.class, ITrx.TRXNAME_None);
	}

	@Override
	public boolean hasBOMs(final I_M_Product product)
	{
		final IQueryBuilder<I_PP_Product_BOM> queryBuilder = Services.get(IQueryBL.class)
				.createQueryBuilder(I_PP_Product_BOM.class, product);

		final ICompositeQueryFilter<I_PP_Product_BOM> filters = queryBuilder.getCompositeFilter();
		filters.addEqualsFilter(I_PP_Product_BOM.COLUMNNAME_M_Product_ID, product.getM_Product_ID());
		filters.addOnlyActiveRecordsFilter();

		return queryBuilder
				.create()
				.match();
	}

	@Override
	public IQuery<I_PP_Product_BOMLine> retrieveBOMLinesForProductQuery(final Properties ctx, final int productId, final String trxName)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_PP_Product_BOMLine.class, ctx, trxName)
				.addEqualsFilter(I_PP_Product_BOMLine.COLUMNNAME_M_Product_ID, productId)
				.addOnlyActiveRecordsFilter()
				.addOnlyContextClient(ctx)
				.create();
	}

	@Override
	public List<I_PP_Product_BOM> retrieveBOMsContainingExactProducts(final Collection<Integer> productIds)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilderOutOfTrx(I_PP_Product_BOM.class)
				.addOnlyActiveRecordsFilter()
				.filter(MatchBOMProductsFilter.exactProducts(productIds))
				.create()
				.listImmutable(I_PP_Product_BOM.class);
	}

	@lombok.ToString
	@lombok.EqualsAndHashCode
	private static final class MatchBOMProductsFilter implements IQueryFilter<I_PP_Product_BOM>, ISqlQueryFilter
	{
		public static MatchBOMProductsFilter exactProducts(final Collection<Integer> productIds)
		{
			return new MatchBOMProductsFilter(productIds);
		}

		private static final String SQL = "("
				+ " select array_agg(distinct bl.M_Product_ID order by bl.M_Product_ID)"
				+ " from " + I_PP_Product_BOMLine.Table_Name + " bl"
				+ " where bl.PP_Product_BOM_ID=PP_Product_BOM.PP_Product_BOM_ID"
				+ " and bl.IsActive='Y'"
				+ ") = ?::numeric[]";

		private final ImmutableList<Object> sqlParams;

		public MatchBOMProductsFilter(final Collection<Integer> productIds)
		{
			Check.assumeNotEmpty(productIds, "productIds is not empty");

			final TreeSet<Integer> productIdsSortedSet = new TreeSet<>(productIds);
			final String sqlProductIdsArray = toSqlArrayString(productIdsSortedSet);
			sqlParams = ImmutableList.<Object> of(sqlProductIdsArray);
		}

		@Override
		public boolean accept(final I_PP_Product_BOM model)
		{
			throw new UnsupportedOperationException("not implemented");
		}

		@Override
		public String getSql()
		{
			return SQL;
		}

		@Override
		public List<Object> getSqlParams(final Properties ctx_NOTUSED)
		{
			return sqlParams;
		}

		private static final String toSqlArrayString(final Collection<Integer> ids)
		{
			final StringBuilder sql = new StringBuilder();
			sql.append("{");
			Joiner.on(",").appendTo(sql, ids);
			sql.append("}");
			return sql.toString();
		}
	}
}
