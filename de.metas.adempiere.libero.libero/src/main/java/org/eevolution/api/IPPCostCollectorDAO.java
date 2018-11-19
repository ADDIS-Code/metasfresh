package org.eevolution.api;

import java.math.BigDecimal;
import java.time.Duration;

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

import java.util.List;

import org.eevolution.model.I_PP_Cost_Collector;
import org.eevolution.model.I_PP_Order;
import org.eevolution.model.I_PP_Order_BOMLine;

import de.metas.costing.CostDetail;
import de.metas.material.planning.pporder.PPOrderBOMLineId;
import de.metas.material.planning.pporder.PPOrderId;
import de.metas.util.ISingletonService;

public interface IPPCostCollectorDAO extends ISingletonService
{

	List<I_PP_Cost_Collector> retrieveForOrderBOMLine(I_PP_Order_BOMLine orderBOMLine);

	BigDecimal getQtyUsageVariance(PPOrderBOMLineId orderBOMLineId);

	List<I_PP_Cost_Collector> retrieveForParent(I_PP_Cost_Collector parentCostCollector);

	List<CostDetail> retrieveCostDetails(I_PP_Cost_Collector cc);

	/**
	 * @deprecated please use {@link #retrieveForOrderId(int)}
	 */
	@Deprecated
	List<I_PP_Cost_Collector> retrieveForOrder(I_PP_Order order);

	I_PP_Cost_Collector getById(int costCollectorId);

	List<I_PP_Cost_Collector> retrieveForOrderId(PPOrderId ppOrderId);

	/**
	 * Retrieve the cost collectors of the given <code>order</code> that are active and are either completed or closed.
	 * 
	 * @param order
	 * @return
	 */
	List<I_PP_Cost_Collector> retrieveNotReversedForOrder(I_PP_Order order);

	/**
	 * Retrieve the cost collectors for the given ppOrder. The cost collectors must have:
	 * <ul>
	 * <li>ppOrder's id
	 * <li>status completed
	 * <li>type Material receipt
	 * </ul>
	 * 
	 * @param ppOrder
	 * @return
	 */
	List<I_PP_Cost_Collector> retrieveExistingReceiptCostCollector(I_PP_Order ppOrder);

	Duration getTotalSetupTimeReal(PPOrderRoutingActivity activity, CostCollectorType costCollectorType);

	Duration getDurationReal(PPOrderRoutingActivity activity, CostCollectorType costCollectorType);
}
