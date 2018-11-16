package de.metas.contracts.refund.invoicecandidatehandler;

import static java.math.BigDecimal.ONE;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.Adempiere;

import com.google.common.collect.ImmutableList;

import de.metas.contracts.ConditionsId;
import de.metas.contracts.invoicecandidate.ConditionTypeSpecificInvoiceCandidateHandler;
import de.metas.contracts.model.I_C_Flatrate_Term;
import de.metas.contracts.model.X_C_Flatrate_Term;
import de.metas.contracts.refund.RefundConfig;
import de.metas.contracts.refund.RefundConfigQuery;
import de.metas.contracts.refund.RefundConfigRepository;
import de.metas.invoicecandidate.model.I_C_Invoice_Candidate;
import de.metas.invoicecandidate.spi.IInvoiceCandidateHandler.PriceAndTax;
import de.metas.product.ProductId;
import de.metas.util.collections.CollectionUtils;
import lombok.NonNull;

/*
 * #%L
 * de.metas.contracts
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

public class FlatrateTermRefund_Handler
		implements ConditionTypeSpecificInvoiceCandidateHandler
{
	@Override
	public String getConditionsType()
	{
		return X_C_Flatrate_Term.TYPE_CONDITIONS_Refund;
	}

	/**
	 * @return an empty iterator; invoice candidates that need to be there are created from {@link InvoiceCandidateAssignmentService}.
	 */
	@Override
	public Iterator<I_C_Flatrate_Term> retrieveTermsWithMissingCandidates(final int limit)
	{
		return ImmutableList
				.<I_C_Flatrate_Term> of()
				.iterator();
	}

	@Override
	public boolean isMissingInvoiceCandidate(final I_C_Flatrate_Term flatrateTerm)
	{
		return false;
	}

	/**
	 * Does nothing
	 */
	@Override
	public void setSpecificInvoiceCandidateValues(
			@NonNull final I_C_Invoice_Candidate ic,
			@NonNull final I_C_Flatrate_Term term)
	{
		// nothing to do
	}

	/**
	 * @return always {@link BigDecimal#ONE}
	 */
	@Override
	public BigDecimal calculateQtyOrdered(@NonNull final I_C_Invoice_Candidate invoiceCandidateRecord)
	{
		return ONE;
	}

	/**
	 * @return {@link PriceAndTax#NONE} because the tax remains unchanged and the price is updated in {@link InvoiceCandidateAssignmentService}.
	 */
	@Override
	public PriceAndTax calculatePriceAndTax(@NonNull final I_C_Invoice_Candidate invoiceCandidateRecord)
	{
		return PriceAndTax.NONE; // no changes to be made
	}

	@Override
	public Consumer<I_C_Invoice_Candidate> getSetInvoiceScheduleImplementation(
			Consumer<I_C_Invoice_Candidate> IGNORED_defaultImplementation)
	{
		return ic -> {
			final RefundConfigRepository refundConfigRepository = Adempiere.getBean(RefundConfigRepository.class);

			final I_C_Flatrate_Term flatrateTermRecord = TableRecordReference.ofReferenced(ic).getModel(I_C_Flatrate_Term.class);
			final BigDecimal minQty = ic.getQtyInvoiced().add(ic.getQtyToInvoice());

			final RefundConfigQuery query = RefundConfigQuery.builder()
					.productId(ProductId.ofRepoId(flatrateTermRecord.getM_Product_ID()))
					.conditionsId(ConditionsId.ofRepoId(flatrateTermRecord.getC_Flatrate_Conditions_ID()))
					.minQty(minQty)
					.build();

			// because we provided product, conditions and minQty there may be only one matching config.
			final List<RefundConfig> refundConfigs = refundConfigRepository.getByQuery(query);
			final RefundConfig refundConfig = CollectionUtils.singleElement(refundConfigs);

			ic.setC_InvoiceSchedule_ID(refundConfig.getInvoiceSchedule().getId().getRepoId());
		};
	}

	@Override
	public Timestamp calculateDateOrdered(@NonNull final I_C_Invoice_Candidate invoiceCandidateRecord)
	{
		return invoiceCandidateRecord.getDateOrdered();
	}
}
