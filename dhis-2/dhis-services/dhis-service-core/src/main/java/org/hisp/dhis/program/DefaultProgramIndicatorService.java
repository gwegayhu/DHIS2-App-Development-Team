package org.hisp.dhis.program;

/*
 * Copyright (c) 2004-2018, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import static org.hisp.dhis.parser.ParserUtils.castClass;
import static org.hisp.dhis.parser.ParserUtils.castString;

import java.util.*;

import org.apache.commons.lang.StringUtils;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.IdentifiableObjectStore;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.constant.ConstantService;
import org.hisp.dhis.dataelement.DataElementService;
import org.hisp.dhis.i18n.I18nManager;
import org.hisp.dhis.jdbc.StatementBuilder;
import org.hisp.dhis.parser.Parser;
import org.hisp.dhis.parser.ParserException;
import org.hisp.dhis.relationship.RelationshipTypeService;
import org.hisp.dhis.trackedentity.TrackedEntityAttributeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Chau Thu Tran
 */
public class DefaultProgramIndicatorService
    implements ProgramIndicatorService
{
    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private ProgramIndicatorStore programIndicatorStore;

    public void setProgramIndicatorStore( ProgramIndicatorStore programIndicatorStore )
    {
        this.programIndicatorStore = programIndicatorStore;
    }

    private ProgramStageService programStageService;

    public void setProgramStageService( ProgramStageService programStageService )
    {
        this.programStageService = programStageService;
    }

    private DataElementService dataElementService;

    public void setDataElementService( DataElementService dataElementService )
    {
        this.dataElementService = dataElementService;
    }

    private TrackedEntityAttributeService attributeService;

    public void setAttributeService( TrackedEntityAttributeService attributeService )
    {
        this.attributeService = attributeService;
    }

    private ConstantService constantService;

    public void setConstantService( ConstantService constantService )
    {
        this.constantService = constantService;
    }

    private StatementBuilder statementBuilder;

    public void setStatementBuilder( StatementBuilder statementBuilder )
    {
        this.statementBuilder = statementBuilder;
    }

    private IdentifiableObjectStore<ProgramIndicatorGroup> programIndicatorGroupStore;

    public void setProgramIndicatorGroupStore(
        IdentifiableObjectStore<ProgramIndicatorGroup> programIndicatorGroupStore )
    {
        this.programIndicatorGroupStore = programIndicatorGroupStore;
    }

    @Autowired
    private IdentifiableObjectManager manager;

    @Autowired
    private RelationshipTypeService relationshipTypeService;

    @Autowired
    private I18nManager i18nManager;

    // -------------------------------------------------------------------------
    // ProgramIndicator CRUD
    // -------------------------------------------------------------------------

    @Transactional
    public int addProgramIndicator( ProgramIndicator programIndicator )
    {
        programIndicatorStore.save( programIndicator );
        return programIndicator.getId();
    }

    @Transactional
    public void updateProgramIndicator( ProgramIndicator programIndicator )
    {
        programIndicatorStore.update( programIndicator );
    }

    @Transactional
    public void deleteProgramIndicator( ProgramIndicator programIndicator )
    {
        programIndicatorStore.delete( programIndicator );
    }

    @Transactional
    public ProgramIndicator getProgramIndicator( int id )
    {
        return programIndicatorStore.get( id );
    }

    @Transactional
    public ProgramIndicator getProgramIndicator( String name )
    {
        return programIndicatorStore.getByName( name );
    }

    @Transactional
    public ProgramIndicator getProgramIndicatorByUid( String uid )
    {
        return programIndicatorStore.getByUid( uid );
    }

    @Transactional
    public List<ProgramIndicator> getAllProgramIndicators()
    {
        return programIndicatorStore.getAll();
    }

    // -------------------------------------------------------------------------
    // ProgramIndicator logic
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    @Deprecated public String getUntypedDescription( String expression )
    {
        return getDescription( expression, null );
    }

    @Override
    @Transactional
    public String getExpressionDescription( String expression )
    {
        return getDescription( expression, Double.class );
    }

    @Override
    @Transactional
    public String getFilterDescription( String expression )
    {
        return getDescription( expression, Boolean.class );
    }

    @Override
    @Transactional
    public boolean expressionIsValid( String expression )
    {
        return isValid( expression, Double.class );
    }

    @Override
    @Transactional
    public boolean filterIsValid( String filter )
    {
        return isValid( filter, Boolean.class );
    }

    @Override
    public void validate( String expression, Class clazz, Map<String, String> itemDescriptions )
    {
        ProgramValidator programExpressionValidator = new ProgramValidator(
            this, constantService, programStageService,
            dataElementService, attributeService, relationshipTypeService,
            i18nManager.getI18n(), itemDescriptions );

        castClass( clazz, Parser.visit( expression, programExpressionValidator ) );
    }

    @Override
    public String getExpressionAnalyticsSql( ProgramIndicator programIndicator, Date startDate, Date endDate )
    {
        return getAnalyticsSql( programIndicator.getExpression(), programIndicator, startDate, endDate, true );
    }

    @Override
    public String getFilterAnalyticsSql( ProgramIndicator programIndicator, Date startDate, Date endDate )
    {
        return getAnalyticsSql( programIndicator.getFilter(), programIndicator, startDate, endDate, false );
    }

    @Override
    public String getAnalyticsSql( String expression, ProgramIndicator programIndicator, Date startDate, Date endDate, boolean ignoreMissingValues )
    {
        if ( expression == null )
        {
            return null;
        }

        Set<String> dataElementAndAttributeIdentifiers = getDataElementAndAttributeIdentifiers(
            expression, programIndicator.getAnalyticsType() );

        ProgramSqlGenerator programSqlGenerator = new ProgramSqlGenerator( programIndicator, startDate, endDate,
            ignoreMissingValues, dataElementAndAttributeIdentifiers, constantService.getConstantMap(),
            this, statementBuilder, dataElementService, attributeService );

        return castString( Parser.visit( expression, programSqlGenerator ) );
    }

    @Override
    public String getAnyValueExistsClauseAnalyticsSql( String expression, AnalyticsType analyticsType )
    {
        if ( expression == null )
        {
            return null;
        }

        try
        {
            Set<String> uids = getDataElementAndAttributeIdentifiers( expression, analyticsType );

            if ( uids.isEmpty() )
            {
                return null;
            }

            String sql = StringUtils.EMPTY;

            for ( String uid : uids )
            {
                sql += statementBuilder.columnQuote( uid ) + " is not null or ";
            }

            return TextUtils.removeLastOr( sql ).trim();
        }
        catch ( ParserException e )
        {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // ProgramIndicatorGroup
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public int addProgramIndicatorGroup( ProgramIndicatorGroup programIndicatorGroup )
    {
        programIndicatorGroupStore.save( programIndicatorGroup );
        return programIndicatorGroup.getId();
    }

    @Override
    @Transactional
    public void updateProgramIndicatorGroup( ProgramIndicatorGroup programIndicatorGroup )
    {
        programIndicatorGroupStore.update( programIndicatorGroup );
    }

    @Override
    @Transactional
    public void deleteProgramIndicatorGroup( ProgramIndicatorGroup programIndicatorGroup )
    {
        programIndicatorGroupStore.delete( programIndicatorGroup );
    }

    @Override
    public ProgramIndicatorGroup getProgramIndicatorGroup( int id )
    {
        return programIndicatorGroupStore.get( id );
    }

    @Override
    public ProgramIndicatorGroup getProgramIndicatorGroup( String uid )
    {
        return programIndicatorGroupStore.getByUid( uid );
    }

    @Override
    public List<ProgramIndicatorGroup> getAllProgramIndicatorGroups()
    {
        return programIndicatorGroupStore.getAll();
    }

    // -------------------------------------------------------------------------
    // Supportive methods
    // -------------------------------------------------------------------------

    private String getDescription( String expression, Class clazz )
    {
        Map<String, String> itemDescriptions = new HashMap<>();

        validate( expression, clazz, itemDescriptions );

        String description = expression;

        for ( Map.Entry<String, String> entry : itemDescriptions.entrySet() )
        {
            description = description.replace( entry.getKey(), entry.getValue() );
        }

        return description;
    }

    private boolean isValid( String expression, Class clazz )
    {
        if ( expression != null )
        {
            try
            {
                validate( expression, clazz, new HashMap<>() );
            }
            catch ( ParserException e )
            {
                return false;
            }
        }

        return true;
    }

    private Set<String> getDataElementAndAttributeIdentifiers( String expression, AnalyticsType analyticsType )
    {
        Set<String> items = new HashSet<>();

        ProgramElementsAndAttributesCollecter listener = new ProgramElementsAndAttributesCollecter( items, analyticsType );

        Parser.listen( expression, listener );

        return items;
    }}
