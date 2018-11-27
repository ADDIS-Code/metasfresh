/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution *
 * This program is free software; you can redistribute it and/or modify it *
 * under the terms version 2 of the GNU General Public License as published *
 * by the Free Software Foundation. This program is distributed in the hope *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. *
 * See the GNU General Public License for more details. *
 * You should have received a copy of the GNU General Public License along *
 * with this program; if not, write to the Free Software Foundation, Inc., *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA. *
 * For the text or an alternative of this public license, you may reach us *
 * Copyright (C) 2003-2007 e-Evolution,SC. All Rights Reserved. *
 * Contributor(s): Victor Perez www.e-evolution.com *
 * Teo Sarca, www.arhipac.ro *
 *****************************************************************************/

package org.eevolution.process;

/*
 * #%L
 * de.metas.adempiere.libero.libero
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.adempiere.mm.attributes.AttributeSetInstanceId;
import org.adempiere.service.ClientId;
import org.adempiere.service.OrgId;
import org.adempiere.util.LegacyAdapters;
import org.compiere.Adempiere;
import org.compiere.model.I_M_Cost;
import org.compiere.model.I_M_CostElement;
import org.compiere.model.I_M_Product;
import org.compiere.model.MProduct;
import org.compiere.model.Query;
import org.compiere.util.DB;
import org.eevolution.api.IProductBOMDAO;
import org.eevolution.model.I_PP_Product_BOM;
import org.eevolution.model.I_PP_Product_BOMLine;
import org.eevolution.model.I_PP_Product_Planning;
import org.eevolution.model.MPPProductBOMLine;
import org.eevolution.model.MPPProductPlanning;
import org.eevolution.model.X_PP_Order_BOMLine;
import org.eevolution.mrp.api.IMRPDAO;

import com.google.common.collect.ImmutableList;

import de.metas.acct.api.AcctSchema;
import de.metas.acct.api.AcctSchemaId;
import de.metas.acct.api.IAcctSchemaDAO;
import de.metas.costing.CostAmount;
import de.metas.costing.CostElement;
import de.metas.costing.CostElementId;
import de.metas.costing.CostSegment;
import de.metas.costing.CostSegmentAndElement;
import de.metas.costing.CostTypeId;
import de.metas.costing.CostingLevel;
import de.metas.costing.CostingMethod;
import de.metas.costing.CurrentCost;
import de.metas.costing.ICostElementRepository;
import de.metas.costing.ICurrentCostsRepository;
import de.metas.costing.IProductCostingBL;
import de.metas.money.CurrencyId;
import de.metas.process.JavaProcess;
import de.metas.process.ProcessInfoParameter;
import de.metas.product.IProductDAO;
import de.metas.product.ProductCategoryId;
import de.metas.product.ProductId;
import de.metas.util.Check;
import de.metas.util.Services;

/**
 * Roll-UP Bill of Material
 *
 * @author victor.perez@e-evolution.com, e-Evolution, S.C.
 * @version $Id: RollupBillOfMaterial.java,v 1.1 2004/06/22 05:24:03 vpj-cd Exp $
 *
 * @author Teo Sarca, www.arhipac.ro
 */
@SuppressWarnings("deprecation") // hide those to not polute our Warnings
public class RollupBillOfMaterial extends JavaProcess
{
	// services
	private final transient ICurrentCostsRepository currentCostsRepo = Adempiere.getBean(ICurrentCostsRepository.class);
	private final transient ICostElementRepository costElementsRepo = Adempiere.getBean(ICostElementRepository.class);
	private final transient IProductDAO productsRepo = Services.get(IProductDAO.class);
	private final transient IProductBOMDAO productBOMsRepo = Services.get(IProductBOMDAO.class);
	private final transient IProductCostingBL productCostingBL = Services.get(IProductCostingBL.class);
	private final transient IMRPDAO mrpDAO = Services.get(IMRPDAO.class);
	private final transient IAcctSchemaDAO acctSchemasRepo = Services.get(IAcctSchemaDAO.class);

	/* Organization */
	private OrgId p_AD_Org_ID;
	/* Account Schema */
	private AcctSchema acctSchema;
	// private AcctSchemaId p_C_AcctSchema_ID;

	/* Cost Type */
	private CostTypeId p_M_CostType_ID;
	/* Costing Method */
	private CostingMethod p_ConstingMethod = CostingMethod.StandardCosting;
	/* Product */
	private ProductId p_M_Product_ID;
	/* Product Category */
	private ProductCategoryId p_M_Product_Category_ID;
	/* Product Type */
	private String p_ProductType = null;

	/**
	 * Prepare - e.g., get Parameters.
	 */
	@Override
	protected void prepare()
	{
		for (final ProcessInfoParameter para : getParametersAsArray())
		{
			final String name = para.getParameterName();

			if (para.getParameter() == null)
			{
				;
			}
			else if (name.equals(I_M_Cost.COLUMNNAME_AD_Org_ID))
			{
				p_AD_Org_ID = OrgId.ofRepoIdOrNull(para.getParameterAsInt());
			}
			else if (name.equals(I_M_Cost.COLUMNNAME_C_AcctSchema_ID))
			{
				AcctSchemaId p_C_AcctSchema_ID = AcctSchemaId.ofRepoId(para.getParameterAsInt());
				acctSchema = Services.get(IAcctSchemaDAO.class).getById(p_C_AcctSchema_ID);
			}
			else if (name.equals(I_M_Cost.COLUMNNAME_M_CostType_ID))
			{
				p_M_CostType_ID = CostTypeId.ofRepoIdOrNull(para.getParameterAsInt());
			}
			else if (name.equals(I_M_CostElement.COLUMNNAME_CostingMethod))
			{
				p_ConstingMethod = CostingMethod.ofNullableCode(para.getParameterAsString());
			}
			else if (name.equals(I_M_Cost.COLUMNNAME_M_Product_ID))
			{
				p_M_Product_ID = ProductId.ofRepoIdOrNull(para.getParameterAsInt());
			}
			else if (name.equals(I_M_Product.COLUMNNAME_M_Product_Category_ID))
			{
				p_M_Product_Category_ID = ProductCategoryId.ofRepoIdOrNull(para.getParameterAsInt());
			}
			else if (name.equals(I_M_Product.COLUMNNAME_ProductType))
			{
				p_ProductType = para.getParameterAsString();
			}
			else
			{
				log.error("prepare - Unknown Parameter: " + name);
			}
		}
	}	// prepare

	/**
	 * Generate Calculate Cost
	 * 
	 * @return info
	 * @throws Exception
	 */
	@Override
	protected String doIt() throws Exception
	{
		resetCostsLLForLLC0();
		//
		final int maxLowLevel = mrpDAO.getMaxLowLevel(this);
		// Cost Roll-up for all levels
		for (int lowLevel = maxLowLevel; lowLevel >= 0; lowLevel--)
		{
			for (final MProduct product : getProducts(lowLevel))
			{
				final I_PP_Product_Planning pp = MPPProductPlanning.find(
						getCtx(),
						p_AD_Org_ID.getRepoId(),
						0, // M_Warehouse_ID
						0, // S_Resource_ID
						product.getM_Product_ID(),
						get_TrxName());

				int PP_Product_BOM_ID = 0;
				if (pp != null)
				{
					PP_Product_BOM_ID = pp.getPP_Product_BOM_ID();
				}
				else
				{
					createNotice(product, "@NotFound@ @PP_Product_Planning_ID@");
				}
				if (PP_Product_BOM_ID <= 0)
				{
					PP_Product_BOM_ID = productBOMsRepo.retrieveDefaultBOMId(product);
				}
				final I_PP_Product_BOM bom = productBOMsRepo.retrieveBOMById(getCtx(), PP_Product_BOM_ID);
				if (bom == null)
				{
					createNotice(product, "@NotFound@ @PP_Product_BOM_ID@");
				}
				rollup(product, bom);
			} // for each Products
		} // for each LLC
		return "@OK@";
	}

	protected void rollup(MProduct product, I_PP_Product_BOM bom)
	{
		for (final CostElement costElement : getCostElements())
		{
			final CostElementId costElementId = costElement.getId();
			
			for (final CurrentCost cost : getCosts(product, costElementId))
			{
				log.info("Calculate Lower Cost for: {}", bom);
				final CostAmount price = getCurrentCostPriceLL(bom, costElement);
				log.info("{} Cost Low Level: {}", costElement, price);
				cost.setCurrentCostPriceLL(price);
				updateCoProductCosts(bom, cost, costElementId);
				currentCostsRepo.save(cost);
			} // for each Costs
		} // for Elements
	}

	/**
	 * Update costs for co-products on BOM Lines from given BOM
	 *
	 * @param bom product's BOM
	 * @param costElement cost element
	 * @param baseCost base product cost (BOM Cost)
	 * @param costElement
	 */
	private void updateCoProductCosts(final I_PP_Product_BOM bom, final CurrentCost baseCost, final CostElementId costElementId)
	{
		// Skip if not BOM found
		if (bom == null)
		{
			return;
		}

		CostAmount costPriceTotal = zeroCosts();
		for (I_PP_Product_BOMLine bomline : productBOMsRepo.retrieveLines(bom))
		{
			final MPPProductBOMLine bomLinePO = LegacyAdapters.convertToPO(bomline);

			if (!bomLinePO.isCoProduct())
			{
				continue;
			}
			final CostAmount costPrice = baseCost.getCurrentCostPriceLL().multiply(getCostAllocationPerc(bomLinePO));

			//
			// Get/Create Cost
			final ProductId productId = ProductId.ofRepoId(bomline.getM_Product_ID());
			final CostSegment costSegment = createCostSegment(baseCost.getCostSegment(), productId);
			final CurrentCost cost = currentCostsRepo.getOrCreate(costSegment.withCostElementId(costElementId));
			cost.setCurrentCostPriceLL(costPrice);
			currentCostsRepo.save(cost);

			costPriceTotal = costPriceTotal.add(costPrice);
		}
		// Update Base Cost:
		if (costPriceTotal.signum() != 0)
		{
			baseCost.setCurrentCostPriceLL(costPriceTotal);
		}
	}

	private CostSegment createCostSegment(final CostSegment costSegment, final ProductId productId)
	{
		final AcctSchema acctSchema = acctSchemasRepo.getById(costSegment.getAcctSchemaId());

		final I_M_Product product = productsRepo.getById(productId);
		final CostingLevel costingLevel = productCostingBL.getCostingLevel(product, acctSchema);

		return costSegment.withProductIdAndCostingLevel(productId, costingLevel);
	}

	/**
	 * @return co-product cost allocation percent (i.e. -1/qty)
	 */
	private BigDecimal getCostAllocationPerc(final MPPProductBOMLine bomLine)
	{
		final BigDecimal qty = bomLine.getQty(false).negate();
		BigDecimal allocationPercent = BigDecimal.ZERO;
		if (qty.signum() != 0)
		{
			allocationPercent = BigDecimal.ONE.divide(qty, 4, RoundingMode.HALF_UP);
		}
		return allocationPercent;
	}

	/**
	 * Get the sum Current Cost Price Level Low for this Cost Element
	 * 
	 * @param bom MPPProductBOM
	 * @param element MCostElement
	 * @return Cost Price Lower Level
	 */
	private CostAmount getCurrentCostPriceLL(final I_PP_Product_BOM bom, final CostElement element)
	{
		log.info("Element: {}", element);

		CostAmount costPriceLL = zeroCosts();
		if (bom == null)
		{
			return costPriceLL;
		}

		for (I_PP_Product_BOMLine bomline : productBOMsRepo.retrieveLines(bom))
		{
			final MPPProductBOMLine bomLinePO = LegacyAdapters.convertToPO(bomline);

			// Skip co-product
			if (bomLinePO.isCoProduct())
			{
				continue;
			}
			// 06005
			if (X_PP_Order_BOMLine.COMPONENTTYPE_Variant.equals(bomline.getComponentType()))
			{
				continue;
			}

			//
			final MProduct component = MProduct.get(getCtx(), bomline.getM_Product_ID());
			// get the rate for this resource
			for (final CurrentCost cost : getCosts(component, element.getId()))
			{
				BigDecimal qty = bomLinePO.getQty(true);

				// ByProducts
				if (bomLinePO.isByProduct())
				{
					cost.setCurrentCostPriceLL(zeroCosts());
				}

				final CostAmount costPrice = cost.getCurrentCostPriceTotal();
				final CostAmount componentCost = costPrice.multiply(qty);
				costPriceLL = costPriceLL.add(componentCost);
				log.info("CostElement: " + element.getName()
						+ ", Component: " + component.getValue()
						+ ", CostPrice: " + costPrice
						+ ", Qty: " + qty
						+ ", Cost: " + componentCost
						+ " => Total Cost Element: " + costPriceLL);
			} // for each cost
		} // for each BOM line
		return costPriceLL;
	}

	private Collection<CurrentCost> getCosts(final I_M_Product product, final CostElementId costElementId)
	{
		final CostSegmentAndElement costSegmentAndElement = createCostSegmentAndElement(product, costElementId);
		final CurrentCost cost = currentCostsRepo.getOrNull(costSegmentAndElement);
		return cost != null ? ImmutableList.of(cost) : ImmutableList.of();
	}

	private CostSegmentAndElement createCostSegmentAndElement(final I_M_Product product, final CostElementId costElementId)
	{
		final AcctSchema as = getAcctSchema();

		final ProductId productId = ProductId.ofRepoId(product.getM_Product_ID());
		final CostingLevel costingLevel = productCostingBL.getCostingLevel(productId, as);

		return CostSegmentAndElement.builder()
				.costingLevel(costingLevel)
				.acctSchemaId(as.getId())
				.costTypeId(p_M_CostType_ID)
				.productId(productId)
				.clientId(ClientId.ofRepoId(product.getAD_Client_ID()))
				.orgId(p_AD_Org_ID)
				.attributeSetInstanceId(AttributeSetInstanceId.NONE)
				.costElementId(costElementId)
				.build();

	}

	private CurrencyId getCurrencyId()
	{
		final AcctSchema as = getAcctSchema();
		return as.getCurrencyId();
	}

	private AcctSchema getAcctSchema()
	{
		Check.assumeNotNull(acctSchema, "Parameter acctSchema is not null");
		return acctSchema;
	}

	private CostAmount zeroCosts()
	{
		return CostAmount.zero(getCurrencyId());
	}

	private Collection<MProduct> getProducts(final int lowLevel)
	{
		final List<Object> params = new ArrayList<>();
		final StringBuffer whereClause = new StringBuffer("AD_Client_ID=?")
				.append(" AND ").append(I_M_Product.COLUMNNAME_LowLevel).append("=?");
		params.add(getAD_Client_ID());
		params.add(lowLevel);

		whereClause.append(" AND ").append(I_M_Product.COLUMNNAME_IsBOM).append("=?");
		params.add(true);

		if (p_M_Product_ID != null)
		{
			whereClause.append(" AND ").append(I_M_Product.COLUMNNAME_M_Product_ID).append("=?");
			params.add(p_M_Product_ID);
		}
		else if (p_M_Product_Category_ID != null)
		{
			whereClause.append(" AND ").append(I_M_Product.COLUMNNAME_M_Product_Category_ID).append("=?");
			params.add(p_M_Product_Category_ID);
		}
		if (p_M_Product_ID == null && p_ProductType != null)
		{
			whereClause.append(" AND ").append(I_M_Product.COLUMNNAME_ProductType).append("=?");
			params.add(p_ProductType);
		}

		return new Query(getCtx(), MProduct.Table_Name, whereClause.toString(), get_TrxName())
				.setParameters(params)
				.list(MProduct.class);
	}

	/**
	 * Reset LowLevel Costs for products with LowLevel=0 (items)
	 */
	private void resetCostsLLForLLC0()
	{
		final List<Object> params = new ArrayList<>();
		final StringBuffer productWhereClause = new StringBuffer();
		productWhereClause.append("AD_Client_ID=? AND " + I_M_Product.COLUMNNAME_LowLevel + "=?");
		params.add(getAD_Client_ID());
		params.add(0);
		if (p_M_Product_ID != null)
		{
			productWhereClause.append(" AND ").append(I_M_Product.COLUMNNAME_M_Product_ID).append("=?");
			params.add(p_M_Product_ID);
		}
		else if (p_M_Product_Category_ID != null)
		{
			productWhereClause.append(" AND ").append(I_M_Product.COLUMNNAME_M_Product_Category_ID).append("=?");
			params.add(p_M_Product_Category_ID);
		}
		//
		final String sql = "UPDATE M_Cost c SET " + I_M_Cost.COLUMNNAME_CurrentCostPriceLL + "=0"
				+ " WHERE EXISTS (SELECT 1 FROM M_Product p WHERE p.M_Product_ID=c.M_Product_ID"
				+ " AND " + productWhereClause + ")";
		final int no = DB.executeUpdateEx(sql, params.toArray(), get_TrxName());
		log.info("Updated #" + no);
	}

	private List<CostElement> m_costElements = null;

	private List<CostElement> getCostElements()
	{
		if (m_costElements == null)
		{
			m_costElements = costElementsRepo.getByCostingMethod(p_ConstingMethod);
		}
		return m_costElements;
	}

	/**
	 * Create Cost Rollup Notice
	 * 
	 * @param product
	 * @param msg
	 */
	private void createNotice(final MProduct product, final String msg)
	{
		final String productValue = product != null ? product.getValue() : "-";
		addLog("WARNING: Product " + productValue + ": " + msg);
	}
}
