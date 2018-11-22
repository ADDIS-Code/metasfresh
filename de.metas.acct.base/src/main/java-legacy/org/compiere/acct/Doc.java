/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved. *
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
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA *
 * or via info@compiere.org or http://www.compiere.org/license.html *
 *****************************************************************************/
package org.compiere.acct;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.adempiere.acct.api.IDocFactory;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.ClientId;
import org.adempiere.service.ISysConfigBL;
import org.adempiere.service.OrgId;
import org.adempiere.util.lang.IMutable;
import org.adempiere.util.lang.Mutable;
import org.adempiere.util.logging.LoggingHelper;
import org.compiere.model.I_C_BP_BankAccount;
import org.compiere.model.MAccount;
import org.compiere.model.MNote;
import org.compiere.model.MPeriod;
import org.compiere.model.PO;
import org.compiere.model.X_C_DocType;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.TimeUtil;
import org.compiere.util.TrxRunnable2;
import org.compiere.util.Util;
import org.slf4j.Logger;

import de.metas.acct.api.AcctSchema;
import de.metas.acct.api.AcctSchemaGeneralLedger;
import de.metas.acct.api.IAccountDAO;
import de.metas.acct.api.IFactAcctDAO;
import de.metas.acct.api.IFactAcctListenersService;
import de.metas.acct.api.IPostingRequestBuilder.PostImmediate;
import de.metas.acct.api.IPostingService;
import de.metas.currency.ICurrencyBL;
import de.metas.currency.ICurrencyConversionContext;
import de.metas.currency.ICurrencyDAO;
import de.metas.currency.exceptions.NoCurrencyRateFoundException;
import de.metas.document.engine.IDocument;
import de.metas.i18n.IMsgBL;
import de.metas.logging.LogManager;
import de.metas.money.CurrencyId;
import de.metas.product.ProductId;
import de.metas.product.acct.api.ActivityId;
import de.metas.util.Check;
import de.metas.util.Services;

/**
 * Posting Document Root.
 *
 * <pre>
 *  Table               Base Document Types (C_DocType.DocBaseType & AD_Reference_ID=183)
 *      Class           AD_Table_ID
 *  ------------------  ------------------------------
 *  C_Invoice:          ARI, ARC, ARF, API, APC
 *      Doc_Invoice     318 - has C_DocType_ID
 *
 *  C_Payment:          ARP, APP
 *      Doc_Payment     335 - has C_DocType_ID
 *
 *  C_Order:            SOO, POO,  POR (Requisition)
 *      Doc_Order       259 - has C_DocType_ID
 *
 *  M_InOut:            MMS, MMR
 *      Doc_InOut       319 - DocType derived
 *
 *  M_Inventory:        MMI
 *      Doc_Inventory   321 - DocType fixed
 *
 *  M_Movement:         MMM
 *      Doc_Movement    323 - DocType fixed
 *
 *  M_Production:       MMP
 *      Doc_Production  325 - DocType fixed
 *
 * M_Production:        MMO
 *      Doc_CostCollector  330 - DocType fixed
 *
 *  C_BankStatement:    CMB
 *      Doc_Bank        392 - DocType fixed
 *
 *  C_Cash:             CMC
 *      Doc_Cash        407 - DocType fixed
 *
 *  C_Allocation:       CMA
 *      Doc_Allocation  390 - DocType fixed
 *
 *  GL_Journal:         GLJ
 *      Doc_GLJournal   224 = has C_DocType_ID
 *
 *  Matching Invoice    MXI
 *      M_MatchInv      472 - DocType fixed
 *
 *  Matching PO         MXP
 *      M_MatchPO       473 - DocType fixed
 *
 * Project Issue		PJI
 * 	C_ProjectIssue	623 - DocType fixed
 *
 * </pre>
 *
 * @author Jorg Janke
 * @author victor.perez@e-evolution.com, e-Evolution http://www.e-evolution.com
 *         <li>FR [ 2520591 ] Support multiples calendar for Org
 * @see http://sourceforge.net/tracker2/?func=detail&atid=879335&aid=2520591&group_id=176962
 * @version $Id: Doc.java,v 1.6 2006/07/30 00:53:33 jjanke Exp $
 */
public abstract class Doc<DocLineType extends DocLine<?>>
{
	private final String SYSCONFIG_CREATE_NOTE_ON_ERROR = "org.compiere.acct.Doc.createNoteOnPostError";

	// services
	private final transient ISysConfigBL sysConfigBL = Services.get(ISysConfigBL.class);
	protected final transient IMsgBL msgBL = Services.get(IMsgBL.class);
	protected final transient ICurrencyDAO currencyDAO = Services.get(ICurrencyDAO.class);
	protected final transient ICurrencyBL currencyConversionBL = Services.get(ICurrencyBL.class);
	private final transient ITrxManager trxManager = Services.get(ITrxManager.class);
	protected final transient IFactAcctDAO factAcctDAO = Services.get(IFactAcctDAO.class);
	protected final transient IAccountDAO accountDAO = Services.get(IAccountDAO.class);
	private final IDocFactory docFactory;

	/** AR Invoices - ARI */
	public static final String DOCTYPE_ARInvoice = X_C_DocType.DOCBASETYPE_ARInvoice;
	/** AR Credit Memo */
	public static final String DOCTYPE_ARCredit = "ARC";
	/** AR Receipt */
	public static final String DOCTYPE_ARReceipt = "ARR";
	/** AR ProForma */
	public static final String DOCTYPE_ARProForma = "ARF";
	/** AP Invoices */
	public static final String DOCTYPE_APInvoice = "API";
	/** AP Credit Memo */
	public static final String DOCTYPE_APCredit = "APC";
	/** AP Payment */
	public static final String DOCTYPE_APPayment = "APP";
	/** CashManagement Bank Statement */
	public static final String DOCTYPE_BankStatement = "CMB";
	/** CashManagement Cash Journals */
	public static final String DOCTYPE_CashJournal = "CMC";
	/** CashManagement Allocations */
	public static final String DOCTYPE_Allocation = "CMA";
	/** Material Shipment */
	public static final String DOCTYPE_MatShipment = "MMS";
	/** Material Receipt */
	public static final String DOCTYPE_MatReceipt = "MMR";
	/** Material Inventory */
	public static final String DOCTYPE_MatInventory = "MMI";
	/** Material Movement */
	public static final String DOCTYPE_MatMovement = "MMM";
	/** Material Production */
	public static final String DOCTYPE_MatProduction = "MMP";
	/** Match Invoice */
	public static final String DOCTYPE_MatMatchInv = "MXI";
	/** Match PO */
	public static final String DOCTYPE_MatMatchPO = "MXP";
	/** GL Journal */
	public static final String DOCTYPE_GLJournal = "GLJ";
	/** Purchase Order */
	public static final String DOCTYPE_POrder = "POO";
	/** Sales Order */
	public static final String DOCTYPE_SOrder = "SOO";
	/** Project Issue */
	public static final String DOCTYPE_ProjectIssue = "PJI";
	/** Purchase Requisition */
	public static final String DOCTYPE_PurchaseRequisition = "POR";

	/** Log per Document */
	private final Logger log = LogManager.getLogger(getClass());

	/**
	 * @param docBuilder construction parameters
	 */
	/* package */ Doc(final IDocBuilder docBuilder)
	{
		this(docBuilder, (String)null); // defaultDocBaseType=null
	}

	/**
	 * @param docBuilder construction parameters
	 * @param defaultDocBaseType suggested DocBaseType to be used
	 */
	/* package */ Doc(final IDocBuilder docBuilder, final String defaultDocBaseType)
	{
		super();

		Check.assumeNotNull(docBuilder, "docBuilder not null");

		//
		// Document Factory
		this.docFactory = docBuilder.getDocFactory();
		Check.assumeNotNull(docFactory, "docFactory not null");

		//
		// Accounting schemas
		Check.assumeNotEmpty(docBuilder.getAcctSchemas(), "ass not empty");
		acctSchemas = docBuilder.getAcctSchemas();

		//
		// Document model
		final Object documentModel = docBuilder.getDocumentModel();
		p_po = InterfaceWrapperHelper.getPO(documentModel);
		Check.assumeNotNull(p_po, "p_po not null");

		//
		// Setup a new context
		final Properties ctx = InterfaceWrapperHelper.getCtx(p_po);
		this.m_ctx = Env.deriveCtx(ctx);
		final AcctSchema acctSchema1 = acctSchemas.get(0); // first account schema
		Env.setContext(m_ctx, Env.CTXNAME_AD_Client_ID, acctSchema1.getClientId().getRepoId());

		//
		// DB Transaction
		this.m_trxName = InterfaceWrapperHelper.getTrxName(p_po);

		// DocStatus
		{
			final int index = p_po.get_ColumnIndex("DocStatus");
			if (index >= 0)
			{
				m_DocStatus = (String)p_po.get_Value(index);
			}
			else
			{
				m_DocStatus = null; // no DocStatus (e.g. M_MatchInv etc)
			}
		}

		// Document Type
		setDocumentType(defaultDocBaseType);
	}   // Doc

	/** Accounting Schema Array */
	private final List<AcctSchema> acctSchemas;
	/** Properties */
	private final Properties m_ctx;
	/** Transaction Name */
	private String m_trxName = null;
	/** The Document */
	private final PO p_po;
	/** Document Type */
	private String m_DocumentType = null;
	/** Document Status */
	private final String m_DocStatus;
	/** Document No */
	private String m_DocumentNo = null;
	/** Description */
	private String m_Description = null;
	/** GL Category */
	private int m_GL_Category_ID = 0;
	/** GL Period */
	private MPeriod m_period = null;
	/** Period ID */
	private int m_C_Period_ID = 0;
	/** Location From */
	private int m_C_LocFrom_ID = 0;
	/** Location To */
	private int m_C_LocTo_ID = 0;
	/** Accounting Date */
	private LocalDate m_DateAcct = null;
	/** Document Date */
	private LocalDate m_DateDoc = null;
	/** Is (Source) Multi-Currency Document - i.e. the document has different currencies (if true, the document will not be source balanced) */
	private boolean m_MultiCurrency = false;
	/** BP Sales Region */
	private int m_BP_C_SalesRegion_ID = -1;
	/** B Partner */
	private int m_C_BPartner_ID = -1;

	/** Bank Account */
	private int m_C_BP_BankAccount_ID = -1;
	private I_C_BP_BankAccount bpBankAccount = null;
	/** Cach Book */
	private int m_C_CashBook_ID = -1;

	private Optional<CurrencyId> _currencyId; // lazy
	private Integer _currencyPrecision = -1; // lazy

	/** Contained Doc Lines */
	private List<DocLineType> docLines;

	// /** No Currency in Document Indicator (-2) */
	// protected static final int NO_CURRENCY = -2;

	protected final Properties getCtx()
	{
		return m_ctx;
	}

	protected final String get_TableName()
	{
		return getPO().get_TableName();
	}

	protected final int get_Table_ID()
	{
		return getPO().get_Table_ID();
	}

	/**
	 * @return record id
	 */
	protected final int get_ID()
	{
		return getPO().get_ID();
	}

	/**
	 * Get Persistent Object
	 *
	 * @return po
	 */
	private final PO getPO()
	{
		return p_po;
	}	// getPO

	protected final <T> T getModel(final Class<T> modelClass)
	{
		return InterfaceWrapperHelper.create(getPO(), modelClass);
	}

	public final void setDocLines(final List<DocLineType> docLines)
	{
		this.docLines = docLines;
	}

	protected final List<DocLineType> getDocLines()
	{
		return docLines;
	}

	/**
	 * Post Document.
	 *
	 * <pre>
	 *  - try to lock document (Processed='Y' (AND Processing='N' AND Posted='N'))
	 * 		- if not ok - return false
	 *          - postlogic (for all Accounting Schema)
	 *              - create Fact lines
	 *          - postCommit
	 *              - commits Fact lines and Document & sets Processing = 'N'
	 *              - if error - create Note
	 * </pre>
	 *
	 * @param force if true ignore that locked
	 * @param repost if true ignore that already posted
	 * @return null if posted error otherwise
	 */
	public final String post(final boolean force, final boolean repost)
	{
		final String trxNameInitial = getTrxName();

		//
		// Lock Document (in parent transaction)
		try
		{
			lock(trxNameInitial, force, repost);
		}
		catch (final Exception e)
		{
			final String errmsg = e.getLocalizedMessage();
			log.error("Failed to lock: " + errmsg, e);
			return errmsg;
		}

		//
		// Do the actual posting
		final IMutable<PostingException> error = new Mutable<>(null);
		final String trxNameOrPrefix;
		final boolean manageTrx;
		if (trxManager.isNull(trxNameInitial))
		{
			trxNameOrPrefix = "Post_" + get_TableName() + "_" + get_ID();
			manageTrx = true; // create a new trx
		}
		else
		{
			trxNameOrPrefix = trxNameInitial;
			manageTrx = false; // use existing transaction
		}
		trxManager.run(trxNameOrPrefix, manageTrx, new TrxRunnable2()
		{
			@Override
			public void run(final String localTrxName) throws Exception
			{
				//
				// Set local transaction name
				setTrxName(localTrxName);

				//
				// Do the actual posting
				post0(force, repost);
			}

			@Override
			public boolean doCatch(final Throwable e) throws Throwable
			{
				final PostingException postingException = newPostingException(e);
				error.setValue(postingException);

				final boolean createNote = sysConfigBL.getBooleanValue(SYSCONFIG_CREATE_NOTE_ON_ERROR, false, getAD_Client_ID(), getAD_Org_ID());
				if (createNote)
				{
					createErrorNote(postingException);
				}
				LoggingHelper.log(log, postingException.getLogLevel(), postingException.getLocalizedMessage(), postingException);

				return true; // rollack, but don't throw the error
			}

			@Override
			public void doFinally()
			{
				// restore transaction
				setTrxName(trxNameInitial);

				//
				// Unlock (in parent transaction)
				final PostingException postingException = error.getValue();
				unlock(trxNameInitial, postingException);
			}
		});

		//
		// Return the error message or null (backward compatibility)
		final PostingException postingException = error.getValue();
		final String errorMsg = postingException == null ? null : postingException.getLocalizedMessage();
		return errorMsg;
	}

	private final void post0(final boolean force, final boolean repost)
	{
		//
		// Validate document's DocStatus
		if (m_DocStatus == null)
		{
			// This is a valid case (e.g. M_MatchInv, M_MatchPO)
		}
		else if (m_DocStatus.equals(IDocument.STATUS_Completed)
				|| m_DocStatus.equals(IDocument.STATUS_Closed)
				|| m_DocStatus.equals(IDocument.STATUS_Voided)
				|| m_DocStatus.equals(IDocument.STATUS_Reversed))
		{
			;
		}
		else
		{
			final String errmsg = "Invalid DocStatus='" + m_DocStatus + "' for DocumentNo=" + getDocumentNo();
			throw newPostingException()
					.setPreserveDocumentPostedStatus()
					.setDetailMessage(errmsg);
		}

		//
		// Validate document's AD_Client_ID
		if (!getClientId().equals(acctSchemas.get(0).getClientId()))
		{
			final String errmsg = "AD_Client_ID Conflict - Document=" + getClientId()
					+ ", AcctSchema=" + acctSchemas.get(0).getClientId();
			throw newPostingException()
					.setPreserveDocumentPostedStatus()
					.setDetailMessage(errmsg);
		}

		//
		// Load document details
		try
		{
			loadDocumentDetails();
		}
		catch (final Exception ex)
		{
			throw newPostingException(ex)
					.setPreserveDocumentPostedStatus();
		}

		//
		// Delete existing Accounting
		if (repost)
		{
			if (isPosted() && !isPeriodOpen())	// already posted - don't delete if period closed
			{
				throw newPostingException()
						.setPreserveDocumentPostedStatus()
						.setDetailMessage("@PeriodClosed@");
			}

			// delete existing accounting records
			deleteAcct();
		}
		else if (isPosted())
		{
			throw newPostingException()
					.setPreserveDocumentPostedStatus()
					.setDetailMessage("@AlreadyPosted@");
		}

		//
		// Create Fact per AcctSchema
		final List<Fact> facts = new ArrayList<>();
		// for all Accounting Schema
		{
			for (final AcctSchema acctSchema : acctSchemas)
			{
				// if acct schema has "only" org, skip
				boolean skip = false;
				if (acctSchema.isPostOnlyForSomeOrgs())
				{
					// Header Level Org
					skip = acctSchema.isDisallowPostingForOrg(getOrgId());
					// Line Level Org
					final List<DocLineType> docLines = getDocLines();
					if (docLines != null)
					{
						for (int line = 0; skip && line < docLines.size(); line++)
						{
							skip = acctSchema.isDisallowPostingForOrg(docLines.get(line).getOrgId());
							if (!skip)
							{
								break;
							}
						}
					}
				}
				if (skip)
				{
					continue;
				}

				// post
				final List<Fact> factsForAcctSchema = postLogic(acctSchema);
				facts.addAll(factsForAcctSchema);
			}
		}

		//
		// Fire event: BEFORE_POST
		final IFactAcctListenersService factAcctListenersService = Services.get(IFactAcctListenersService.class);
		factAcctListenersService.fireBeforePost(getPO());

		//
		// Save facts
		// p_Status = postCommit (p_Status);
		for (final Fact fact : facts)
		{
			// Skip null facts
			if (fact == null)
			{
				continue;
			}

			fact.save(getTrxName());
		}

		//
		// Fire event: AFTER_POST
		factAcctListenersService.fireAfterPost(getPO());

		//
		// Execute after document posted code
		afterPost();

		//
		// Dispose facts
		// Dispose lines
		for (Fact fact : facts)
		{
			if (fact != null)
			{
				fact.dispose();
			}
		}
	}

	/**
	 * Delete Accounting
	 *
	 * @return number of records deleted
	 */
	private final int deleteAcct()
	{
		final Object documentPO = getPO();
		return factAcctDAO.deleteForDocumentModel(documentPO);
	}	// deleteAcct

	/**
	 * Posting logic for Accounting Schema
	 *
	 * @param acctSchema Accounting Schema
	 * @return
	 */
	private final List<Fact> postLogic(final AcctSchema acctSchema)
	{
		// rejectUnbalanced
		final AcctSchemaGeneralLedger acctSchemaGL = acctSchema.getGeneralLedger();
		if (!acctSchemaGL.isSuspenseBalancing() && !isBalanced())
		{
			throw newPostingException()
					.setAcctSchema(acctSchema)
					.setPostingStatus(PostingStatus.NotBalanced);
		}

		// rejectUnconvertible
		checkConvertible(acctSchema);

		// rejectPeriodClosed
		if (!isPeriodOpen())
		{
			throw newPostingException()
					.setAcctSchema(acctSchema)
					.setPostingStatus(PostingStatus.PeriodClosed);
		}

		//
		// Create facts for accounting schema
		final List<Fact> facts = createFacts(acctSchema);
		if (facts == null)
		{
			throw newPostingException()
					.setAcctSchema(acctSchema)
					.setPostingStatus(PostingStatus.Error)
					.setDetailMessage("No facts");
		}

		for (final Fact fact : facts)
		{
			if (fact == null)
			{
				throw newPostingException()
						.setAcctSchema(acctSchema)
						.setPostingStatus(PostingStatus.Error)
						.setDetailMessage("No fact");
			}

			//
			// p_Status = STATUS_PostPrepared;

			// check accounts
			if (!fact.checkAccounts())
			{
				throw newPostingException()
						.setAcctSchema(acctSchema)
						.setPostingStatus(PostingStatus.InvalidAccount)
						.setFact(fact);
			}

			// distribute
			try
			{
				fact.distribute();
			}
			catch (final Exception e)
			{
				throw newPostingException(e)
						.setAcctSchema(acctSchema)
						.setPostingStatus(PostingStatus.Error)
						.setFact(fact)
						.setDetailMessage("Fact distribution error: " + e.getLocalizedMessage());
			}

			// Balance source amounts
			if (!fact.isSourceBalanced())
			{
				fact.balanceSource();
				if (!fact.isSourceBalanced())
				{
					throw newPostingException()
							.setAcctSchema(acctSchema)
							.setPostingStatus(PostingStatus.NotBalanced)
							.setFact(fact)
							.setDetailMessage("Source amounts not balanced");
				}
			}

			// balanceSegments
			if (!fact.isSegmentBalanced())
			{
				fact.balanceSegments();
				if (!fact.isSegmentBalanced())
				{
					throw newPostingException()
							.setAcctSchema(acctSchema)
							.setPostingStatus(PostingStatus.NotBalanced)
							.setFact(fact)
							.setDetailMessage("Segment not balanced");
				}
			}

			// balanceAccounting
			if (!fact.isAcctBalanced())
			{
				fact.balanceAccounting();
				if (!fact.isAcctBalanced())
				{
					throw newPostingException()
							.setAcctSchema(acctSchema)
							.setPostingStatus(PostingStatus.NotBalanced)
							.setFact(fact)
							.setDetailMessage("Accountable amounts not balanced");
				}
			}
		}	// for all facts

		// return STATUS_Posted;
		return facts;
	}   // postLogic

	/**
	 * Get Trx Name and create Transaction
	 *
	 * @return Trx Name
	 */
	protected final String getTrxName()
	{
		return m_trxName;
	}	// getTrxName

	private final void setTrxName(final String trxName)
	{
		this.m_trxName = trxName;

		// NOTE: we are also updating PO's trxName because there are some retrieval methods which could depend on PO's trxName.
		// Our am is not to allow changing and saving the PO.
		if (p_po != null)
		{
			p_po.set_TrxName(trxName);
		}
	}

	/**
	 * Lock document
	 *
	 * @param force force posting
	 * @param repost true if is document re-posting; i.e. it will assume the document was not already posted
	 * @throws AdempiereException in case of failure
	 */
	private final void lock(final String trxName, final boolean force, final boolean repost)
	{
		final StringBuilder sql = new StringBuilder("UPDATE ");
		sql.append(get_TableName()).append(" SET Processing='Y' WHERE ")
				.append(get_TableName()).append("_ID=").append(get_ID())
				.append(" AND Processed='Y' AND IsActive='Y'");
		if (!force)
		{
			sql.append(" AND (Processing='N' OR Processing IS NULL)");
		}
		if (!repost)
		{
			sql.append(" AND Posted='N'");
		}

		final int updatedCount = DB.executeUpdateEx(sql.toString(), trxName);
		if (updatedCount == 1)
		{
			log.info("Locked: " + get_TableName() + "_ID=" + get_ID());
		}
		else
		{
			final PO po = getPO();
			final String errmsg = force ? "Cannot Lock - ReSubmit" : "Cannot Lock - ReSubmit or RePost with Force";
			throw newPostingException()
					.setDetailMessage(errmsg)
					.addDetailMessage("Hint: it could be that for some reason, the document remained locked (i.e. Processing=Y), so you could unlock it to fix the issue.")
					.setParameter("Processing", po.get_Value("Processing"))
					.setParameter("Processed", po.get_Value("Processed"))
					.setParameter("IsActive", po.get_Value("IsActive"))
					.setParameter("Posted", po.get_Value("Posted"))
					.setParameter("SQL", sql.toString())
					.setPostingStatus(PostingStatus.NotPosted)
					.setPreserveDocumentPostedStatus();
		}
	}

	private final void unlock(final String trxName, final PostingException exception)
	{
		final String tableName = get_TableName();
		final String keyColumnName = tableName + "_ID";
		final int recordId = get_ID();

		final StringBuilder sql = new StringBuilder("UPDATE ")
				.append(tableName).append(" SET ");

		// Unlock it
		sql.append("Processing='N'");

		final boolean updatePostedStatus = exception != null && !exception.isPreserveDocumentPostedStatus();
		if (exception == null)
		{
			sql.append(", Posted=").append(DB.TO_STRING(PostingStatus.Posted.getStatusCode()));
		}
		else if (updatePostedStatus)
		{
			final PostingStatus postingStatus = exception.getPostingStatus(PostingStatus.Error);
			sql.append(", Posted=").append(DB.TO_STRING(postingStatus.getStatusCode()));
		}

		sql.append(" WHERE ").append(keyColumnName).append("=").append(recordId);
		final int updateCount = DB.executeUpdateEx(sql.toString(), trxName);
		if (updateCount != 1)
		{
			throw newPostingException()
					.setDetailMessage("Unable to unlock");
		}
	}

	/**************************************************************************
	 * Load Document Type and GL Info. Set p_DocumentType and p_GL_Category_ID
	 *
	 * @return document type (i.e. DocBaseType)
	 */
	protected final String getDocumentType()
	{
		if (m_DocumentType == null)
		{
			setDocumentType(null);
		}
		return m_DocumentType;
	}   // getDocumentType

	/**
	 * Load Document Type and GL Info. Set p_DocumentType and p_GL_Category_ID
	 *
	 * @param docBaseType optional document base type to be used.
	 */
	private final void setDocumentType(final String docBaseType)
	{
		if (docBaseType != null)
		{
			m_DocumentType = docBaseType;
		}

		// No Document Type defined
		if (m_DocumentType == null && getC_DocType_ID() > 0)
		{
			final String sql = "SELECT DocBaseType, GL_Category_ID FROM C_DocType WHERE C_DocType_ID=?";
			PreparedStatement pstmt = null;
			ResultSet rsDT = null;
			try
			{
				pstmt = DB.prepareStatement(sql, ITrx.TRXNAME_None);
				pstmt.setInt(1, getC_DocType_ID());
				rsDT = pstmt.executeQuery();
				if (rsDT.next())
				{
					m_DocumentType = rsDT.getString(1);
					m_GL_Category_ID = rsDT.getInt(2);
				}
			}
			catch (final SQLException e)
			{
				log.error(sql, e);
			}
			finally
			{
				DB.close(rsDT, pstmt);
				rsDT = null;
				pstmt = null;
			}
		}
		if (m_DocumentType == null)
		{
			log.error("No DocBaseType for C_DocType_ID=" + getC_DocType_ID() + ", DocumentNo=" + getDocumentNo());
		}

		// We have a document Type, but no GL info - search for DocType
		if (m_GL_Category_ID <= 0)
		{
			final String sql = "SELECT GL_Category_ID FROM C_DocType WHERE AD_Client_ID=? AND DocBaseType=?";
			PreparedStatement pstmt = null;
			ResultSet rsDT = null;
			try
			{
				pstmt = DB.prepareStatement(sql, ITrx.TRXNAME_None);
				pstmt.setInt(1, getAD_Client_ID());
				pstmt.setString(2, m_DocumentType);
				rsDT = pstmt.executeQuery();
				if (rsDT.next())
				{
					m_GL_Category_ID = rsDT.getInt(1);
				}
			}
			catch (final SQLException e)
			{
				log.error(sql, e);
			}
			finally
			{
				DB.close(rsDT, pstmt);
			}
		}

		// Still no GL_Category - get Default GL Category
		if (m_GL_Category_ID <= 0)
		{
			final String sql = "SELECT GL_Category_ID FROM GL_Category "
					+ "WHERE AD_Client_ID=? "
					+ "ORDER BY IsDefault DESC";
			PreparedStatement pstmt = null;
			ResultSet rsDT = null;
			try
			{
				pstmt = DB.prepareStatement(sql, ITrx.TRXNAME_None);
				pstmt.setInt(1, getAD_Client_ID());
				rsDT = pstmt.executeQuery();
				if (rsDT.next())
				{
					m_GL_Category_ID = rsDT.getInt(1);
				}
				rsDT.close();
				pstmt.close();
			}
			catch (final SQLException e)
			{
				log.error(sql, e);
			}
			finally
			{
				DB.close(rsDT, pstmt);
			}
		}
		//
		if (m_GL_Category_ID <= 0)
		{
			log.error("No default GL_Category - " + toString());
		}

		if (m_DocumentType == null)
		{
			throw new IllegalStateException("Document Type not found");
		}
	}	// setDocumentType

	/**************************************************************************
	 * Is the Source Document Balanced
	 *
	 * @return true if (source) balanced
	 */
	private final boolean isBalanced()
	{
		// Multi-Currency documents are source balanced by definition
		if (isMultiCurrency())
		{
			return true;
		}
		//
		final boolean retValue = getBalance().signum() == 0;
		if (retValue)
		{
			log.debug("Yes - {}", this);
		}
		else
		{
			log.warn("NO - {}", this);
		}
		return retValue;
	}	// isBalanced

	/**
	 * Makes sure the document is convertible from it's currency to accounting currency.
	 *
	 * @param acctSchema accounting schema
	 */
	private final void checkConvertible(final AcctSchema acctSchema)
	{
		// No Currency in document
		final CurrencyId docCurrencyId = getCurrencyId();
		if (docCurrencyId == null)
		{
			log.debug("(none) - {}", this);
			return;
		}

		// Get All Currencies
		final Set<CurrencyId> currencyIds = new HashSet<>();
		currencyIds.add(docCurrencyId);

		final List<DocLineType> docLines = getDocLines();
		if (docLines != null)
		{
			for (final DocLineType docLine : docLines)
			{
				final CurrencyId currencyId = docLine.getCurrencyId();
				if (currencyId != null)
				{
					currencyIds.add(currencyId);
				}
			}
		}

		// Check
		final CurrencyId acctCurrencyId = acctSchema.getCurrencyId();
		for (final CurrencyId currencyId : currencyIds)
		{
			if (CurrencyId.equals(currencyId, acctCurrencyId))
			{
				continue;
			}

			final ICurrencyConversionContext conversionCtx = currencyConversionBL.createCurrencyConversionContext(
					TimeUtil.asDate(getDateAcct()), 
					getC_ConversionType_ID(), 
					getAD_Client_ID(), 
					getAD_Org_ID());
			try
			{
				currencyConversionBL.getCurrencyRate(conversionCtx, currencyId.getRepoId(), acctCurrencyId.getRepoId());
			}
			catch (final NoCurrencyRateFoundException e)
			{
				throw newPostingException(e)
						.setAcctSchema(acctSchema)
						.setPostingStatus(PostingStatus.NotConvertible);
			}
		}
	}

	/**
	 * Calculate Period from DateAcct. m_C_Period_ID is set to -1 of not open to 0 if not found
	 */
	private final void setPeriod()
	{
		if (m_period != null)
		{
			return;
		}

		// Period defined in GL Journal (e.g. adjustment period)
		final int periodId = getValueAsIntOrZero("C_Period_ID");
		if (periodId > 0)
		{
			m_period = MPeriod.get(getCtx(), periodId);
		}
		if (m_period == null)
		{
			m_period = MPeriod.get(getCtx(), TimeUtil.asTimestamp(getDateAcct()), getAD_Org_ID());
		}

		// Is Period Open?
		if (m_period != null
				&& m_period.isOpen(getDocumentType(), TimeUtil.asTimestamp(getDateAcct()), getAD_Org_ID()))
		{
			m_C_Period_ID = m_period.getC_Period_ID();
		}
		else
		{
			m_C_Period_ID = -1;
		}
	}

	protected final int getC_Period_ID()
	{
		if (m_period == null)
		{
			setPeriod();
		}
		return m_C_Period_ID;
	}	// getC_Period_ID

	/**
	 * Is Period Open
	 *
	 * @return true if period is open
	 */
	private final boolean isPeriodOpen()
	{
		setPeriod();
		final boolean open = m_C_Period_ID > 0;
		if (open)
		{
			log.debug("Yes - " + toString());
		}
		else
		{
			log.warn("NO - " + toString());
		}
		return open;
	}	// isPeriodOpen

	/*************************************************************************/

	/** Amount Type - Invoice - Gross */
	public static final int AMTTYPE_Gross = 0;
	/** Amount Type - Invoice - Net */
	public static final int AMTTYPE_Net = 1;
	/** Amount Type - Invoice - Charge */
	public static final int AMTTYPE_Charge = 2;

	/** Source Amounts (may not all be used) */
	private final BigDecimal[] m_Amounts = new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };

	/**
	 * Get the Amount (loaded in loadDocumentDetails)
	 *
	 * @param AmtType see AMTTYPE_*
	 * @return Amount
	 */
	protected final BigDecimal getAmount(final int AmtType)
	{
		if (AmtType < 0 || AmtType >= m_Amounts.length)
		{
			return null;
		}
		return m_Amounts[AmtType];
	}	// getAmount

	/**
	 * Set the Amount
	 *
	 * @param AmtType see AMTTYPE_*
	 * @param amt Amount
	 */
	protected final void setAmount(final int AmtType, final BigDecimal amt)
	{
		if (AmtType < 0 || AmtType >= m_Amounts.length)
		{
			return;
		}
		if (amt == null)
		{
			m_Amounts[AmtType] = BigDecimal.ZERO;
		}
		else
		{
			m_Amounts[AmtType] = amt;
		}
	}	// setAmount

	/**
	 * Get Amount with index 0
	 *
	 * @return Amount (primary document amount)
	 */
	protected final BigDecimal getAmount()
	{
		return m_Amounts[0];
	}   // getAmount

	/** Account Type - Invoice - Charge */
	public static final int ACCTTYPE_Charge = 0;
	/** Account Type - Invoice - AR */
	public static final int ACCTTYPE_C_Receivable = 1;
	/** Account Type - Invoice - AP */
	public static final int ACCTTYPE_V_Liability = 2;
	/** Account Type - Invoice - AP Service */
	public static final int ACCTTYPE_V_Liability_Services = 3;
	/** Account Type - Invoice - AR Service */
	public static final int ACCTTYPE_C_Receivable_Services = 4;

	/** Account Type - Payment - Unallocated */
	public static final int ACCTTYPE_UnallocatedCash = 10;
	/** Account Type - Payment - Transfer */
	public static final int ACCTTYPE_BankInTransit = 11;
	/** Account Type - Payment - Selection */
	public static final int ACCTTYPE_PaymentSelect = 12;
	/** Account Type - Payment - Prepayment */
	public static final int ACCTTYPE_C_Prepayment = 13;
	/** Account Type - Payment - Prepayment */
	public static final int ACCTTYPE_V_Prepayment = 14;

	/** Account Type - Cash - Asset */
	public static final int ACCTTYPE_CashAsset = 20;
	/** Account Type - Cash - Transfer */
	public static final int ACCTTYPE_CashTransfer = 21;
	/** Account Type - Cash - Expense */
	public static final int ACCTTYPE_CashExpense = 22;
	/** Account Type - Cash - Receipt */
	public static final int ACCTTYPE_CashReceipt = 23;
	/** Account Type - Cash - Difference */
	public static final int ACCTTYPE_CashDifference = 24;

	/** Account Type - Allocation - Discount Expense (AR) */
	public static final int ACCTTYPE_DiscountExp = 30;
	/** Account Type - Allocation - Discount Revenue (AP) */
	public static final int ACCTTYPE_DiscountRev = 31;
	/** Account Type - Allocation - Write Off */
	public static final int ACCTTYPE_WriteOff = 32;

	/** Account Type - Bank Statement - Asset */
	public static final int ACCTTYPE_BankAsset = 40;
	/** Account Type - Bank Statement - Interest Revenue */
	public static final int ACCTTYPE_InterestRev = 41;
	/** Account Type - Bank Statement - Interest Exp */
	public static final int ACCTTYPE_InterestExp = 42;

	/** Inventory Accounts - Differences */
	public static final int ACCTTYPE_InvDifferences = 50;
	/** Inventory Accounts - NIR */
	public static final int ACCTTYPE_NotInvoicedReceipts = 51;

	/** Project Accounts - Assets */
	public static final int ACCTTYPE_ProjectAsset = 61;
	/** Project Accounts - WIP */
	public static final int ACCTTYPE_ProjectWIP = 62;

	/** GL Accounts - PPV Offset */
	public static final int ACCTTYPE_PPVOffset = 101;
	/** GL Accounts - Commitment Offset */
	public static final int ACCTTYPE_CommitmentOffset = 111;
	/** GL Accounts - Commitment Offset Sales */
	public static final int ACCTTYPE_CommitmentOffsetSales = 112;

	/**
	 * Get the Valid Combination id for Accounting Schema
	 *
	 * @param AcctType see ACCTTYPE_*
	 * @param as accounting schema
	 * @return C_ValidCombination_ID
	 */
	protected final int getValidCombination_ID(final int AcctType, final AcctSchema as)
	{
		final int para_1;     // first parameter (second is always AcctSchema)
		final String sql;

		/** Account Type - Invoice */
		if (AcctType == ACCTTYPE_Charge)	// see getChargeAccount in DocLine
		{
			final int cmp = getAmount(AMTTYPE_Charge).compareTo(BigDecimal.ZERO);
			if (cmp == 0)
			{
				return 0;
			}
			else if (cmp < 0)
			{
				sql = "SELECT CH_Expense_Acct FROM C_Charge_Acct WHERE C_Charge_ID=? AND C_AcctSchema_ID=?";
			}
			else
			{
				sql = "SELECT CH_Revenue_Acct FROM C_Charge_Acct WHERE C_Charge_ID=? AND C_AcctSchema_ID=?";
			}
			para_1 = getC_Charge_ID();
		}
		else if (AcctType == ACCTTYPE_V_Liability)
		{
			sql = "SELECT V_Liability_Acct FROM C_BP_Vendor_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_V_Liability_Services)
		{
			sql = "SELECT V_Liability_Services_Acct FROM C_BP_Vendor_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_C_Receivable)
		{
			sql = "SELECT C_Receivable_Acct FROM C_BP_Customer_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_C_Receivable_Services)
		{
			sql = "SELECT C_Receivable_Services_Acct FROM C_BP_Customer_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_V_Prepayment)
		{
			// metas: changed per Mark request: don't use prepayment account:
			log.warn("V_Prepayment account shall not be used", new Exception());
			sql = "SELECT V_Prepayment_Acct FROM C_BP_Vendor_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_C_Prepayment)
		{
			// metas: changed per Mark request: don't use prepayment account:
			log.warn("C_Prepayment account shall not be used", new Exception());
			sql = "SELECT C_Prepayment_Acct FROM C_BP_Customer_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}

		/** Account Type - Payment */
		else if (AcctType == ACCTTYPE_UnallocatedCash)
		{
			sql = "SELECT B_UnallocatedCash_Acct FROM C_BP_BankAccount_Acct WHERE C_BP_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BP_BankAccount_ID();
		}
		else if (AcctType == ACCTTYPE_BankInTransit)
		{
			sql = "SELECT B_InTransit_Acct FROM C_BP_BankAccount_Acct WHERE C_BP_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BP_BankAccount_ID();
		}
		else if (AcctType == ACCTTYPE_PaymentSelect)
		{
			sql = "SELECT B_PaymentSelect_Acct FROM C_BP_BankAccount_Acct WHERE C_BP_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BP_BankAccount_ID();
		}

		/** Account Type - Allocation */
		else if (AcctType == ACCTTYPE_DiscountExp)
		{
			sql = "SELECT a.PayDiscount_Exp_Acct FROM C_BP_Group_Acct a, C_BPartner bp "
					+ "WHERE a.C_BP_Group_ID=bp.C_BP_Group_ID AND bp.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_DiscountRev)
		{
			sql = "SELECT PayDiscount_Rev_Acct FROM C_BP_Group_Acct a, C_BPartner bp "
					+ "WHERE a.C_BP_Group_ID=bp.C_BP_Group_ID AND bp.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_WriteOff)
		{
			sql = "SELECT WriteOff_Acct FROM C_BP_Group_Acct a, C_BPartner bp "
					+ "WHERE a.C_BP_Group_ID=bp.C_BP_Group_ID AND bp.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}

		/** Account Type - Bank Statement */
		else if (AcctType == ACCTTYPE_BankAsset)
		{
			sql = "SELECT B_Asset_Acct FROM C_BP_BankAccount_Acct WHERE C_BP_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BP_BankAccount_ID();
		}
		else if (AcctType == ACCTTYPE_InterestRev)
		{
			sql = "SELECT B_InterestRev_Acct FROM C_BP_BankAccount_Acct WHERE C_BP_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BP_BankAccount_ID();
		}
		else if (AcctType == ACCTTYPE_InterestExp)
		{
			sql = "SELECT B_InterestExp_Acct FROM C_BP_BankAccount_Acct WHERE C_BP_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BP_BankAccount_ID();
		}

		/** Account Type - Cash */
		else if (AcctType == ACCTTYPE_CashAsset)
		{
			sql = "SELECT CB_Asset_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}
		else if (AcctType == ACCTTYPE_CashTransfer)
		{
			sql = "SELECT CB_CashTransfer_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}
		else if (AcctType == ACCTTYPE_CashExpense)
		{
			sql = "SELECT CB_Expense_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}
		else if (AcctType == ACCTTYPE_CashReceipt)
		{
			sql = "SELECT CB_Receipt_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}
		else if (AcctType == ACCTTYPE_CashDifference)
		{
			sql = "SELECT CB_Differences_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}

		/** Inventory Accounts */
		else if (AcctType == ACCTTYPE_InvDifferences)
		{
			sql = "SELECT W_Differences_Acct FROM M_Warehouse_Acct WHERE M_Warehouse_ID=? AND C_AcctSchema_ID=?";
			// "SELECT W_Inventory_Acct, W_Revaluation_Acct, W_InvActualAdjust_Acct FROM M_Warehouse_Acct WHERE M_Warehouse_ID=? AND C_AcctSchema_ID=?";
			para_1 = getM_Warehouse_ID();
		}
		else if (AcctType == ACCTTYPE_NotInvoicedReceipts)
		{
			sql = "SELECT NotInvoicedReceipts_Acct FROM C_BP_Group_Acct a, C_BPartner bp "
					+ "WHERE a.C_BP_Group_ID=bp.C_BP_Group_ID AND bp.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}

		/** Project Accounts */
		else if (AcctType == ACCTTYPE_ProjectAsset)
		{
			sql = "SELECT PJ_Asset_Acct FROM C_Project_Acct WHERE C_Project_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_Project_ID();
		}
		else if (AcctType == ACCTTYPE_ProjectWIP)
		{
			sql = "SELECT PJ_WIP_Acct FROM C_Project_Acct WHERE C_Project_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_Project_ID();
		}

		/** GL Accounts */
		else if (AcctType == ACCTTYPE_PPVOffset)
		{
			sql = "SELECT PPVOffset_Acct FROM C_AcctSchema_GL WHERE C_AcctSchema_ID=?";
			para_1 = -1;
		}
		else if (AcctType == ACCTTYPE_CommitmentOffset)
		{
			sql = "SELECT CommitmentOffset_Acct FROM C_AcctSchema_GL WHERE C_AcctSchema_ID=?";
			para_1 = -1;
		}
		else if (AcctType == ACCTTYPE_CommitmentOffsetSales)
		{
			sql = "SELECT CommitmentOffsetSales_Acct FROM C_AcctSchema_GL WHERE C_AcctSchema_ID=?";
			para_1 = -1;
		}
		else
		{
			log.error("Not found AcctType=" + AcctType);
			return 0;
		}
		// Do we have sql & Parameter
		if (sql == null || para_1 == 0)
		{
			log.error("No Parameter for AcctType=" + AcctType + " - SQL=" + sql);
			return 0;
		}

		// Get Acct
		int Account_ID = 0;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, ITrx.TRXNAME_None);
			if (para_1 == -1)
			{
				pstmt.setInt(1, as.getId().getRepoId());
			}
			else
			{
				pstmt.setInt(1, para_1);
				pstmt.setInt(2, as.getId().getRepoId());
			}
			rs = pstmt.executeQuery();
			if (rs.next())
			{
				Account_ID = rs.getInt(1);
			}
		}
		catch (final SQLException e)
		{
			log.error("AcctType=" + AcctType + " - SQL=" + sql, e);
			return 0;
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null;
			pstmt = null;
		}
		// No account
		if (Account_ID == 0)
		{
			log.error("NO account Type=" + AcctType + ", Record=" + get_ID() + ", SQL=" + sql + ", para_1=" + para_1);
			return 0;
		}
		return Account_ID;
	}	// getAccount_ID

	/**
	 * Get the account for Accounting Schema
	 *
	 * @param AcctType see ACCTTYPE_*
	 * @param as accounting schema
	 * @return Account or <code>null</code>
	 */
	protected final MAccount getAccount(final int AcctType, final AcctSchema as)
	{
		final int C_ValidCombination_ID = getValidCombination_ID(AcctType, as);
		if (C_ValidCombination_ID <= 0)
		{
			return null;
		}
		// Return Account
		final MAccount acct = accountDAO.getById(getCtx(), C_ValidCombination_ID);
		return acct;
	}	// getAccount

	protected final MAccount getRealizedGainAcct(final AcctSchema as)
	{
		return accountDAO.getById(getCtx(), as.getDefaultAccounts().getRealizedGainAcctId());
	}

	protected final MAccount getRealizedLossAcct(final AcctSchema as)
	{
		return accountDAO.getById(getCtx(), as.getDefaultAccounts().getRealizedLossAcctId());
	}

	@Override
	public String toString()
	{
		return getPO().toString();
	}

	@Deprecated
	public final int getAD_Client_ID()
	{
		return getPO().getAD_Client_ID();
	}

	public final ClientId getClientId()
	{
		return ClientId.ofRepoId(getAD_Client_ID());
	}

	@Deprecated
	public final int getAD_Org_ID()
	{
		return getPO().getAD_Org_ID();
	}

	public final OrgId getOrgId()
	{
		return OrgId.ofRepoId(getAD_Org_ID());
	}

	public String getDocumentNo()
	{
		if (m_DocumentNo == null)
		{
			m_DocumentNo = Util.coalesceSuppliers(
					() -> getValueAsString("DocumentNo"),
					() -> getValueAsString("Name"),
					() -> {
						throw new AdempiereException("DocumentNo not found");
					});
		}
		return m_DocumentNo;
	}

	public final String getDocStatus()
	{
		return m_DocStatus;
	}

	public final String getDescription()
	{
		if (m_Description == null)
		{
			m_Description = getValueAsString("Description");
			if (m_Description == null)
			{
				m_Description = "";
			}
		}
		return m_Description;
	}

	public final CurrencyId getCurrencyId()
	{
		if (_currencyId == null)
		{
			final CurrencyId currencyId = CurrencyId.ofRepoIdOrNull(getValueAsIntOrZero("C_Currency_ID"));
			_currencyId = Optional.ofNullable(currencyId);
		}

		return _currencyId.orElse(null);
	}

	protected final void setC_Currency_ID(final CurrencyId currencyId)
	{
		_currencyId = Optional.ofNullable(currencyId);
		_currencyPrecision = null;
	}

	protected final void setNoCurrency()
	{
		final CurrencyId currencyId = null;
		setC_Currency_ID(currencyId);
	}

	public final boolean isMultiCurrency()
	{
		return m_MultiCurrency;
	}

	protected final void setIsMultiCurrency(final boolean mc)
	{
		m_MultiCurrency = mc;
	}

	public final int getC_ConversionType_ID()
	{
		final int conversionTypeId = getValueAsIntOrZero("C_ConversionType_ID");
		return conversionTypeId > 0 ? conversionTypeId : ICurrencyBL.DEFAULT_ConversionType_ID;
	}

	protected final int getStdPrecision()
	{
		if (_currencyPrecision != null)
		{
			return _currencyPrecision;
		}

		final CurrencyId currencyId = getCurrencyId();
		if (currencyId == null)
		{
			return ICurrencyDAO.DEFAULT_PRECISION;
		}

		_currencyPrecision = currencyDAO.getStdPrecision(getCtx(), currencyId.getRepoId());
		return _currencyPrecision;
	}

	public final int getGL_Category_ID()
	{
		return m_GL_Category_ID;
	}

	public final int getGL_Budget_ID()
	{
		return getValueAsIntOrZero("GL_Budget_ID");
	}

	public final LocalDate getDateAcct()
	{
		return Util.coalesceSuppliers(
				() -> m_DateAcct,
				() -> getValueAsLocalDateOrNull("DateAcct"),
				() -> {
					throw new AdempiereException("No DateAcct");
				});
	}

	protected final void setDateAcct(final Timestamp dateAcct)
	{
		m_DateAcct = TimeUtil.asLocalDate(dateAcct);
	}

	public final LocalDate getDateDoc()
	{
		return Util.coalesceSuppliers(
				() -> m_DateDoc,
				() -> getValueAsLocalDateOrNull("DateDoc"),
				() -> getValueAsLocalDateOrNull("MovementDate"),
				() -> {
					throw new AdempiereException("No DateDoc");
				});
	}

	protected final void setDateDoc(final Timestamp dateDoc)
	{
		setDateDoc(TimeUtil.asLocalDate(dateDoc));
	}

	protected final void setDateDoc(final LocalDate dateDoc)
	{
		m_DateDoc = dateDoc;
	}

	public final boolean isPosted()
	{
		final Boolean posted = getValueAsBoolean("Posted", null);
		if (posted == null)
		{
			throw new AdempiereException("Posted column is missing or it's null");
		}
		return posted;
	}

	public final boolean isSOTrx()
	{
		return Util.coalesceSuppliers(
				() -> getValueAsBoolean("IsSOTrx", null),
				() -> getValueAsBoolean("IsReceipt", null),
				() -> false);
	}

	public final int getC_DocType_ID()
	{
		final int docTypeId = getValueAsIntOrZero("C_DocType_ID");
		if (docTypeId > 0)
		{
			return docTypeId;
		}

		// fallback
		final int docTypeTargetId = getValueAsIntOrZero("C_DocTypeTarget_ID");
		return docTypeTargetId;
	}

	public final int getC_Charge_ID()
	{
		return getValueAsIntOrZero("C_Charge_ID");
	}

	public final int getSalesRep_ID()
	{
		return getValueAsIntOrZero("SalesRep_ID");
	}

	/**
	 * Get C_BP_BankAccount_ID if it was previously set using {@link #setC_BP_BankAccount_ID(int)}, or attempts to get it from our <code>p_po</code> (document record).
	 *
	 * @return BankAccount
	 */
	final int getC_BP_BankAccount_ID()
	{
		if (m_C_BP_BankAccount_ID == -1)
		{
			m_C_BP_BankAccount_ID = getValueAsIntOrZero(I_C_BP_BankAccount.COLUMNNAME_C_BP_BankAccount_ID);
			if (m_C_BP_BankAccount_ID <= 0)
			{
				m_C_BP_BankAccount_ID = 0;
			}
		}
		return m_C_BP_BankAccount_ID;
	}

	final void setC_BP_BankAccount_ID(final int C_BP_BankAccount_ID)
	{
		m_C_BP_BankAccount_ID = C_BP_BankAccount_ID;
	}

	/**
	 * @return bank account or <code>null</code>
	 */
	protected final I_C_BP_BankAccount getC_BP_BankAccount()
	{
		final int bpBankAccountId = getC_BP_BankAccount_ID();
		if (bpBankAccountId <= 0)
		{
			return null;
		}
		if (bpBankAccount == null || bpBankAccount.getC_BP_BankAccount_ID() != bpBankAccountId)
		{
			bpBankAccount = InterfaceWrapperHelper.create(getCtx(), bpBankAccountId, I_C_BP_BankAccount.class, ITrx.TRXNAME_None);
		}
		return bpBankAccount;
	}

	public final int getC_CashBook_ID()
	{
		if (m_C_CashBook_ID == -1)
		{
			m_C_CashBook_ID = getValueAsIntOrZero("C_CashBook_ID");
			if (m_C_CashBook_ID <= 0)
			{
				m_C_CashBook_ID = 0;
			}
		}
		return m_C_CashBook_ID;
	}

	protected final void setC_CashBook_ID(final int C_CashBook_ID)
	{
		m_C_CashBook_ID = C_CashBook_ID;
	}

	public final int getM_Warehouse_ID()
	{
		return getValueAsIntOrZero("M_Warehouse_ID");
	}

	/**
	 * Get C_BPartner_ID
	 *
	 * @return BPartner
	 */
	public final int getC_BPartner_ID()
	{
		if (m_C_BPartner_ID == -1)
		{
			m_C_BPartner_ID = getValueAsIntOrZero("C_BPartner_ID");
			if (m_C_BPartner_ID <= 0)
			{
				m_C_BPartner_ID = 0;
			}
		}
		return m_C_BPartner_ID;
	}

	protected final void setC_BPartner_ID(final int C_BPartner_ID)
	{
		m_C_BPartner_ID = C_BPartner_ID;
	}

	public final int getC_BPartner_Location_ID()
	{
		return getValueAsIntOrZero("C_BPartner_Location_ID");
	}

	public final int getC_Project_ID()
	{
		return getValueAsIntOrZero("C_Project_ID");
	}

	public final int getC_SalesRegion_ID()
	{
		return getValueAsIntOrZero("C_SalesRegion_ID");
	}

	public final int getBP_C_SalesRegion_ID()
	{
		if (m_BP_C_SalesRegion_ID == -1)
		{
			m_BP_C_SalesRegion_ID = getC_SalesRegion_ID();
			if (m_BP_C_SalesRegion_ID <= 0)
			{
				m_BP_C_SalesRegion_ID = 0;
			}
		}
		return m_BP_C_SalesRegion_ID;
	}

	/**
	 * Set BPartner's C_SalesRegion_ID
	 */
	protected final void setBP_C_SalesRegion_ID(final int C_SalesRegion_ID)
	{
		m_BP_C_SalesRegion_ID = C_SalesRegion_ID;
	}

	public final ActivityId getC_Activity_ID()
	{
		return ActivityId.ofRepoIdOrNull(getValueAsIntOrZero("C_Activity_ID"));
	}

	public final int getC_Campaign_ID()
	{
		return getValueAsIntOrZero("C_Campaign_ID");
	}

	public final ProductId getProductId()
	{
		return ProductId.ofRepoIdOrNull(getM_Product_ID());
	}

	public final int getM_Product_ID()
	{
		return getValueAsIntOrZero("M_Product_ID");
	}

	public final int getAD_OrgTrx_ID()
	{
		return getValueAsIntOrZero("AD_OrgTrx_ID");
	}

	public final int getC_LocFrom_ID()
	{
		return m_C_LocFrom_ID;
	}

	protected final void setC_LocFrom_ID(final int C_LocFrom_ID)
	{
		m_C_LocFrom_ID = C_LocFrom_ID;
	}

	public final int getC_LocTo_ID()
	{
		return m_C_LocTo_ID;
	}

	protected final void setC_LocTo_ID(final int C_LocTo_ID)
	{
		m_C_LocTo_ID = C_LocTo_ID;
	}

	public final int getUser1_ID()
	{
		return getValueAsIntOrZero("User1_ID");
	}

	public final int getUser2_ID()
	{
		return getValueAsIntOrZero("User2_ID");
	}

	protected final int getValueAsIntOrZero(final String ColumnName)
	{
		final PO po = getPO();
		final int index = po.get_ColumnIndex(ColumnName);
		if (index != -1)
		{
			final Integer ii = (Integer)po.get_Value(index);
			if (ii != null)
			{
				return ii.intValue();
			}
		}
		return 0;
	}	// getValue

	private final LocalDate getValueAsLocalDateOrNull(final String columnName)
	{
		final PO po = getPO();
		final int index = po.get_ColumnIndex(columnName);
		if (index != -1)
		{
			return TimeUtil.asLocalDate(po.get_Value(index));
		}

		return null;
	}

	private final Boolean getValueAsBoolean(final String columnName, final Boolean defaultValue)
	{
		final PO po = getPO();
		final int index = po.get_ColumnIndex(columnName);
		if (index != -1)
		{
			final Object valueObj = po.get_Value(index);
			return DisplayType.toBoolean(valueObj, defaultValue);
		}

		return defaultValue;
	}

	private final String getValueAsString(final String columnName)
	{
		final PO po = getPO();
		final int index = po.get_ColumnIndex(columnName);
		if (index != -1)
		{
			final Object valueObj = po.get_Value(index);
			return valueObj != null ? valueObj.toString() : null;
		}

		return null;
	}

	/**
	 * Load Document Details
	 */
	protected abstract void loadDocumentDetails();

	/**
	 * Get Source Currency Balance - subtracts line (and tax) amounts from total - no rounding
	 *
	 * @return positive amount, if total header is bigger than lines
	 */
	protected abstract BigDecimal getBalance();

	/**
	 * Create Facts (the accounting logic)
	 *
	 * @param as accounting schema
	 * @return Facts
	 */
	protected abstract List<Fact> createFacts(final AcctSchema as);

	/**
	 * Method called after everything was Posted and saved to database, right before committing.
	 */
	protected void afterPost()
	{
		// nothing on this level
	}

	protected final PostingException newPostingException()
	{
		final Throwable e = null;
		return newPostingException(e);
	}

	protected final PostingException newPostingException(final Throwable e)
	{
		final PostingException postingException;
		if (e == null)
		{
			postingException = new PostingException(this);
		}
		else if (e instanceof PostingException)
		{
			postingException = (PostingException)e;
		}
		else
		{
			postingException = new PostingException(this, e)
					.setPostingStatus(PostingStatus.Error);
		}

		if (isPosted())
		{
			postingException.setPreserveDocumentPostedStatus();
		}

		return postingException;
	}

	private final void createErrorNote(final PostingException e)
	{
		DB.saveConstraints();
		try
		{
			DB.getConstraints().setOnlyAllowedTrxNamePrefixes(false).incMaxTrx(1);

			// Insert Note
			final PostingStatus postingStatus = e.getPostingStatus(PostingStatus.Error);
			final String AD_MessageValue = postingStatus.getAD_Message();
			final PO po = getPO();
			final int AD_User_ID = po.getUpdatedBy();
			final MNote note = new MNote(getCtx(), AD_MessageValue, AD_User_ID, getAD_Client_ID(), getAD_Org_ID(), ITrx.TRXNAME_None);
			note.setRecord(po.get_Table_ID(), po.get_ID());
			// Reference
			note.setReference(toString());	// Document
			// Text

			final StringBuilder text = new StringBuilder(msgBL.getMsg(getCtx(), AD_MessageValue));
			final String p_Error = e.getDetailMessage();
			if (!Check.isEmpty(p_Error, true))
			{
				text.append(" (").append(p_Error).append(")");
			}

			final String cn = getClass().getName();
			text.append(" - ").append(cn.substring(cn.lastIndexOf('.')));
			final boolean loaded = getDocLines() != null;
			if (loaded)
			{
				text.append(" (").append(getDocumentType())
						.append(" - DocumentNo=").append(getDocumentNo())
						.append(", DateAcct=").append(getDateAcct().toString().substring(0, 10))
						.append(", Amount=").append(getAmount())
						.append(", Sta=").append(postingStatus)
						.append(" - PeriodOpen=").append(isPeriodOpen())
						.append(", Balanced=").append(isBalanced());
			}
			note.setTextMsg(text.toString());
			note.save();
			// p_Error = Text.toString();
		}
		catch (final Exception ex)
		{
			log.warn("Failed to create the error note. Skipped", ex);
		}
		finally
		{
			DB.restoreConstraints();
		}
	}

	/** @return factory which created this document */
	public final IDocFactory getDocFactory()
	{
		Check.assumeNotNull(docFactory, "docFactory is set");
		return this.docFactory;
	}

	/**
	 * Post immediate given list of documents.
	 *
	 * NOTE:
	 * <ul>
	 * <li>this method won't fail if any of the documents's posting is failing, because we don't want to prevent the main document posting because of this
	 * </ul>
	 *
	 * @param documentModels
	 */
	protected final void postDependingDocuments(final List<?> documentModels)
	{
		if (documentModels == null)
		{
			return; // nothing to do
		}

		// task 08643: the list of documentModels might originate from a bag (i.e. InArrayFilter does not filter at all when given an empty set of values).
		// so we assume that if there are >=200 items, it's that bug and there are not really that many documentModels.
		// Note: we fixed the issue in the method's current callers (Doc_InOut and Doc_Invoice).
		//
		if (documentModels != null && documentModels.size() >= 200)
		{
			final PostingException ex = newPostingException()
					.setDocument(this)
					.setDetailMessage("There are too many depending document models to post. This might be a problem in filtering (legacy-bug in InArrayFilter).");
			log.warn("Got to many depending documents to post. Skip posting depending documents.", ex);
			return;
		}

		final IPostingService postingService = Services.get(IPostingService.class);

		for (final Object document : documentModels)
		{
			postingService.newPostingRequest()
					// Post it in same context and transaction as this document is posted
					.setContext(getCtx(), getTrxName())
					.setClientId(getClientId())
					.setDocumentFromModel(document) // the document to be posted
					.setFailOnError(false) // don't fail because we don't want to fail the main document posting because one of it's depending documents are failing
					.setPostImmediate(PostImmediate.Yes) // yes, post it immediate
					.setForce(false) // don't force it
					.setPostWithoutServer() // post directly (don't contact the server) because we want to post on client or server like the main document
					.postIt(); // do it!
		}
	}
}   // Doc
