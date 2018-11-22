package org.compiere.acct;

import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.OrgId;
import org.compiere.Adempiere;
import org.compiere.model.I_C_OrderLine;
import org.compiere.model.I_M_InOutLine;
import org.compiere.model.MAccount;
import org.compiere.util.DB;
import org.compiere.util.TimeUtil;

import de.metas.acct.api.AcctSchema;
import de.metas.acct.api.AcctSchemaId;
import de.metas.acct.api.ProductAcctType;
import de.metas.costing.CostAmount;
import de.metas.costing.CostDetailCreateRequest;
import de.metas.costing.CostDetailReverseRequest;
import de.metas.costing.CostingDocumentRef;
import de.metas.costing.ICostingService;
import de.metas.quantity.Quantity;

/*
 * #%L
 * de.metas.acct.base
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

class DocLine_InOut extends DocLine<Doc_InOut>
{
	/** Outside Processing */
	private Integer ppCostCollectorId = null;

	public DocLine_InOut(final I_M_InOutLine inoutLine, final Doc_InOut doc)
	{
		super(InterfaceWrapperHelper.getPO(inoutLine), doc);

		final Quantity qty = Quantity.of(inoutLine.getMovementQty(), getProductStockingUOM());
		setQty(qty, doc.isSOTrx());
	}

	private final int getPP_Cost_Collector_ID()
	{
		if (ppCostCollectorId == null)
		{
			ppCostCollectorId = retrievePPCostCollectorId();
		}
		return ppCostCollectorId;
	}

	private final int retrievePPCostCollectorId()
	{
		final int orderLineId = getC_OrderLine_ID();
		if (orderLineId > 0)
		{
			final String sql = "SELECT PP_Cost_Collector_ID  FROM C_OrderLine WHERE C_OrderLine_ID=? AND PP_Cost_Collector_ID IS NOT NULL";
			return DB.getSQLValueEx(getTrxName(), sql, new Object[] { orderLineId });
		}

		return 0;
	}

	public final I_C_OrderLine getOrderLineOrNull()
	{
		return getModel(I_M_InOutLine.class)
				.getC_OrderLine();
	}

	/**
	 * @return order's org if defined, else doc line's org
	 */
	public final OrgId getOrderOrgId()
	{
		final I_C_OrderLine orderLine = getOrderLineOrNull();
		return orderLine != null
				? OrgId.ofRepoId(orderLine.getAD_Org_ID())
				: getOrgId();
	}

	public MAccount getProductAssetAccount(final AcctSchema as)
	{
		if (isItem())
		{
			return getAccount(ProductAcctType.Asset, as);
		}
		// if the line is a Outside Processing then DR WIP
		else if (getPP_Cost_Collector_ID() > 0)
		{
			return getAccount(ProductAcctType.WorkInProcess, as);
		}
		else
		{
			return getAccount(ProductAcctType.Expense, as);
		}
	}

	public CostAmount getCreateReceiptCosts(final AcctSchema as)
	{
		final ICostingService costDetailService = Adempiere.getBean(ICostingService.class);

		final AcctSchemaId acctSchemaId = as.getId();

		if (isReversalLine())
		{
			return costDetailService.createReversalCostDetails(CostDetailReverseRequest.builder()
					.acctSchemaId(acctSchemaId)
					.reversalDocumentRef(CostingDocumentRef.ofReceiptLineId(get_ID()))
					.initialDocumentRef(CostingDocumentRef.ofReceiptLineId(getReversalLine_ID()))
					.date(TimeUtil.asLocalDate(getDateDoc()))
					.build())
					.getTotalAmount();
		}
		else
		{
			return costDetailService.createCostDetail(
					CostDetailCreateRequest.builder()
							.acctSchemaId(acctSchemaId)
							.clientId(getClientId())
							.orgId(getOrgId())
							.productId(getProductId())
							.attributeSetInstanceId(getAttributeSetInstanceId())
							.documentRef(CostingDocumentRef.ofReceiptLineId(get_ID()))
							.qty(getQty())
							.amt(CostAmount.zero(as.getCurrencyId())) // N/A
							.date(TimeUtil.asLocalDate(getDateDoc()))
							.build())
					.getTotalAmount();
		}
	}

	public CostAmount getCreateShipmentCosts(final AcctSchema as)
	{
		final ICostingService costDetailService = Adempiere.getBean(ICostingService.class);

		final AcctSchemaId acctSchemaId = as.getId();

		if (isReversalLine())
		{
			return costDetailService.createReversalCostDetails(CostDetailReverseRequest.builder()
					.acctSchemaId(acctSchemaId)
					.reversalDocumentRef(CostingDocumentRef.ofShipmentLineId(get_ID()))
					.initialDocumentRef(CostingDocumentRef.ofShipmentLineId(getReversalLine_ID()))
					.date(TimeUtil.asLocalDate(getDateAcct()))
					.build())
					.getTotalAmount();
		}
		else
		{
			return costDetailService.createCostDetail(
					CostDetailCreateRequest.builder()
							.acctSchemaId(acctSchemaId)
							.clientId(getClientId())
							.orgId(getOrgId())
							.productId(getProductId())
							.attributeSetInstanceId(getAttributeSetInstanceId())
							.documentRef(CostingDocumentRef.ofShipmentLineId(get_ID()))
							.qty(getQty())
							.amt(CostAmount.zero(as.getCurrencyId())) // expect to be calculated
							.date(TimeUtil.asLocalDate(getDateAcct()))
							.build())
					.getTotalAmount();
		}
	}
}
