package org.hisp.dhis.parser.program;

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

import org.hisp.dhis.common.StringRange;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataelement.DataElementService;
import org.hisp.dhis.jdbc.StatementBuilder;
import org.hisp.dhis.parser.common.CommonSqlGenerator;
import org.hisp.dhis.parser.common.InternalParserException;
import org.hisp.dhis.parser.common.ParserExceptionWithoutContext;
import org.hisp.dhis.parser.common.generated.ExpressionParser;
import org.hisp.dhis.program.AnalyticsType;
import org.hisp.dhis.program.ProgramIndicator;
import org.hisp.dhis.api.util.DateUtils;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.trackedentity.TrackedEntityAttributeService;
import org.springframework.util.Assert;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hisp.dhis.parser.common.ParserUtils.*;
import static org.hisp.dhis.parser.common.generated.ExpressionParser.*;

/**
 * ANTLR parse tree visitor to translate a Program Indicator expression into SQL.
 * <p/>
 * Uses the ANTLR visitor pattern.
 *
 * @author Jim Grace
 */
public class ProgramSqlGenerator
    extends CommonSqlGenerator
{
    private ProgramIndicator programIndicator;

    private Date reportingStartDate;

    private Date reportingEndDate;

    private Set<String> dataElementAndAttributeIdentifiers;

    private Map<String, Double> constantMap;

    private ProgramParserService programParserService;

    private StatementBuilder statementBuilder;

    private DataElementService dataElementService;

    private TrackedEntityAttributeService attributeService;

    public ProgramSqlGenerator( ProgramIndicator programIndicator,
        Date reportingStartDate, Date reportingEndDate, boolean ignoreMissingValues,
        Set<String> dataElementAndAttributeIdentifiers,
        Map<String, Double> constantMap,
        ProgramParserService programParserService,
        StatementBuilder statementBuilder,
        DataElementService dataElementService,
        TrackedEntityAttributeService attributeService )
    {
        this.programIndicator = programIndicator;
        this.reportingStartDate = reportingStartDate;
        this.reportingEndDate = reportingEndDate;
        this.ignoreMissingValues = ignoreMissingValues;
        this.dataElementAndAttributeIdentifiers = dataElementAndAttributeIdentifiers;
        this.constantMap = constantMap;
        this.programParserService = programParserService;
        this.statementBuilder = statementBuilder;
        this.dataElementService = dataElementService;
        this.attributeService = attributeService;
    }

    // -------------------------------------------------------------------------
    // Visitor methods
    // -------------------------------------------------------------------------

    @Override
    public String visitItem( ExpressionParser.ItemContext ctx )
    {
        switch ( ctx.it.getType() )
        {
            case HASH_BRACE:
                if ( !isStageElementSyntax( ctx ) )
                {
                    throw new ParserExceptionWithoutContext( "Invalid Program Stage / DataElement syntax: " + ctx.getText() );
                }

                String programStageId = ctx.uid0.getText();
                String dataElementId = ctx.uid1.getText();

                String column = statementBuilder.getProgramIndicatorDataValueSelectSql(
                    programStageId, dataElementId, reportingStartDate, reportingEndDate, programIndicator );

                if ( ignoreMissingValues )
                {
                    DataElement dataElement = dataElementService.getDataElement( dataElementId );

                    if ( dataElement == null )
                    {
                        throw new ParserExceptionWithoutContext( "Data element " + dataElementId + " not found during SQL generation." );
                    }

                    column = handleMissingValues( column, dataElement.getValueType() );
                }

                return column;

            case A_BRACE:
                if ( !isProgramExpressionProgramAttribute( ctx ) )
                {
                    throw new ParserExceptionWithoutContext( "Program attribute must have one UID: " + ctx.getText() );
                }

                String attributeId = ctx.uid0.getText();

                column = statementBuilder.columnQuote( attributeId );

                if ( ignoreMissingValues )
                {
                    TrackedEntityAttribute attribute = attributeService.getTrackedEntityAttribute( attributeId );

                    if ( attribute == null )
                    {
                        throw new ParserExceptionWithoutContext( "Tracked entity attribute " + attributeId + " not found during SQL generation." );
                    }

                    column = handleMissingValues( column, attribute.getValueType() );
                }

                return column;

            case C_BRACE:
                String constantId = ctx.uid0.getText();

                Double value = constantMap.get( constantId );

                if ( value == null )
                {
                    throw new ParserExceptionWithoutContext( "No constant defined for " + constantId );
                }

                return value.toString();

            default:
                throw new ParserExceptionWithoutContext( "Item not recognized for program expression: " + ctx.getText() );
        }
    }

    @Override
    public String visitProgramVariable( ProgramVariableContext ctx )
    {
        String dbl = statementBuilder.getDoubleColumnType();

        switch ( ctx.var.getType() )
        {
            case V_ANALYTICS_PERIOD_END:
                return statementBuilder.encode( DateUtils.getSqlDateString( reportingEndDate ) );

            case V_ANALYTICS_PERIOD_START:
                return statementBuilder.encode( DateUtils.getSqlDateString( reportingStartDate ) );

            case V_CURRENT_DATE:
                return statementBuilder.encode( DateUtils.getLongDateString() );

            case V_DUE_DATE:
                return "duedate";

            case V_ENROLLMENT_COUNT:
                return "distinct pi";

            case V_ENROLLMENT_DATE:
                return "enrollmentdate";

            case V_ENROLLMENT_STATUS:
                return "enrollmentstatus";

            case V_EVENT_COUNT:
                return "distinct psi";

            case V_EXECUTION_DATE:
            case V_EVENT_DATE:
                return "executiondate";

            case V_INCIDENT_DATE:
                return "incidentdate";

            case V_PROGRAM_STAGE_ID:
                return AnalyticsType.EVENT == programIndicator.getAnalyticsType() ? "ps" : "''";

            case V_PROGRAM_STAGE_NAME:
                return AnalyticsType.EVENT == programIndicator.getAnalyticsType() ?
                    "(select name from programstage where uid = ps)" : "''";

            case V_TEI_COUNT:
                return "distinct tei";

            case V_VALUE_COUNT:
                String sql = "nullif(cast((";

                for ( String uid : dataElementAndAttributeIdentifiers )
                {
                    sql += "case when " + statementBuilder.columnQuote( uid ) + " is not null then 1 else 0 end + ";
                }

                return TextUtils.removeLast( sql, "+" ).trim() + ") as " + dbl + "),0)";

            case V_ZERO_POS_VALUE_COUNT:
                sql = "nullif(cast((";

                for ( String uid : dataElementAndAttributeIdentifiers )
                {
                    sql += "case when " + statementBuilder.columnQuote( uid ) + " >= 0 then 1 else 0 end + ";
                }

                return TextUtils.removeLast( sql, "+" ).trim() + ") as " + dbl + "),0)";

            default: // A program variable defined in the grammar that is not defined here
                throw new InternalParserException( "program variable not recognized: " + ctx.getText() );
        }
    }

    @Override
    public String visitProgramFunction( ProgramFunctionContext ctx )
    {
        boolean savedIgnoreMissingValues = ignoreMissingValues;

        ignoreMissingValues = false;

        String result = visitProgramFunctionInternal( ctx );

        ignoreMissingValues = savedIgnoreMissingValues;

        return result;
    }

    // -------------------------------------------------------------------------
    // Supportive methods
    // -------------------------------------------------------------------------

    private String visitProgramFunctionInternal( ProgramFunctionContext ctx )
    {
        List<String> args = ctx.expr().stream().map( c -> castString( visit( c ) ) ).collect( Collectors.toList() );

        StringRange dates = getDateArgs( ctx );

        switch ( ctx.d2.getType() )
        {
            case D2_CONDITION:
                return "case when (" + getStringLiteralSql( ctx ) + ") then " + args.get( 0 ) + " else " + args.get( 1 ) + " end";

            case D2_COUNT:
                return countWhereCondition( ctx, " is not null" );

            case D2_COUNT_IF_CONDITION:
                return countWhereCondition( ctx, getConditionSql( ctx ) );

            case D2_COUNT_IF_VALUE:
                return countWhereCondition( ctx, " = " + ctx.numStringLiteral().getText() );

            case D2_DAYS_BETWEEN:
                return "(cast(" + dates.getEnd() + " as date) - cast(" + dates.getStart() + " as date))";

            case D2_HAS_VALUE:
                return "((" + hasValueArg( ctx.column() ) + ") is not null)";

            case D2_MINUTES_BETWEEN:
                return "(extract(epoch from (cast(" + dates.getEnd() + " as timestamp) - cast(" + dates.getStart() + " as timestamp))) / 60)";

            case D2_MONTHS_BETWEEN:
                return "(date_part('month',age(cast(" + dates.getEnd() + " as date), cast(" + dates.getStart() + " as date))))";

            case D2_OIZP:
                return "coalesce(case when " + args.get( 0 ) + " >= 0 then 1 else 0 end, 0)";

            case D2_RELATIONSHIP_COUNT:
                return relationshipCount( ctx );

            case D2_WEEKS_BETWEEN:
                return "((cast(" + dates.getEnd() + " as date) - cast(" + dates.getStart() + " as date))/7)";

            case D2_YEARS_BETWEEN:
                return "(date_part('year',age(cast(" + dates.getEnd() + " as date), cast(" + dates.getStart() + " as date))))";

            case D2_ZING:
                return "coalesce(case when " + args.get( 0 ) + " < 0 then 0 else " + args.get( 0 ) + " end, 0)";

            case D2_ZPVC:
                return zeroPositiveValueCount( args );

            default: // A program function defined in the grammar that is not defined here
                throw new InternalParserException( "program function not recognized: " + ctx.getText() );
        }
    }

    private String getStringLiteralSql( ProgramFunctionContext ctx )
    {
        return getSql( trimQuotes( ctx.STRING_LITERAL().getText() ) );
    }

    private String getConditionSql( ProgramFunctionContext ctx )
    {
        String sql = getSql( "0" + trimQuotes( ctx.STRING_LITERAL().getText() ) );

        return sql.substring( 1, sql.length() - 1 );
    }

    private String getSql( String expression )
    {
        return programParserService.getAnalyticsSql( expression, programIndicator,
            reportingStartDate, reportingEndDate, ignoreMissingValues );
    }

    /**
     * Adds logic to ignore missing SQL return column values
     *
     * @param column the column (may be a subquery)
     * @param valueType the type of value that might be missing
     * @return SQL to replace a missing value with 0 or '' depending on type
     */
    private String handleMissingValues( String column, ValueType valueType )
    {
        return valueType.isNumeric() || valueType.isBoolean()
            ? "coalesce(" + column + "::numeric,0)"
            : "coalesce(" + column + ",'')";
    }

    /**
     * Generates a SQL subquery to count a number of values with some condition
     *
     * @param ctx program function parsing context
     * @param condition the condition to apply
     * @return the SQL subquery
     */
    private String countWhereCondition( ProgramFunctionContext ctx, String condition )
    {
        String programStage = ctx.stageDataElement().uid0.getText();
        String dataElement = ctx.stageDataElement().uid1.getText();

        String eventTableName = "analytics_event_" + programIndicator.getProgram().getUid();
        String columnName = "\"" + dataElement + "\"";

        return "(select count(" + columnName + ") from " + eventTableName + " where " + eventTableName +
            ".pi = " + StatementBuilder.ANALYTICS_TBL_ALIAS + ".pi and " + columnName + " is not null " +
            " and " + columnName + condition + " " +
            (programIndicator.getEndEventBoundary() != null ? ("and " +
                statementBuilder.getBoundaryCondition( programIndicator.getEndEventBoundary(), programIndicator,
                    reportingStartDate, reportingEndDate ) +
                " ") : "") + (programIndicator.getStartEventBoundary() != null ? ("and " +
            statementBuilder.getBoundaryCondition( programIndicator.getStartEventBoundary(), programIndicator,
                reportingStartDate, reportingEndDate ) +
            " ") : "") + "and ps = '" + programStage + "')";
    }

    /**
     * Checks program function arguments to see if there are date start and
     * end date arguemnts. If so, they are resolved and returned.
     *
     * @param ctx program function parsing context
     * @return start and end date arguments
     */
    private StringRange getDateArgs( ProgramFunctionContext ctx )
    {
        String start = null;
        String end = null;

        if ( ctx.compareDate().size() != 0 )
        {
            Assert.isTrue( ctx.compareDate().size() == 2, "SQL generator found " + ctx.compareDate().size() + " compare dates" );

            start = getCompareDate( ctx.compareDate( 0 ) );
            end = getCompareDate( ctx.compareDate( 1 ) );
        }

        return new StringRange( start, end );
    }

    /**
     * Resolves a start or end date program function argument
     *
     * @param ctx program function compare date context
     * @return the resolved date
     */
    private String getCompareDate( CompareDateContext ctx )
    {
        if ( ctx.uid0 != null )
        {
            return statementBuilder.getProgramIndicatorColumnSelectSql(
                ctx.uid0.getText(), "executiondate",
                reportingStartDate, reportingEndDate, programIndicator );
        }

        return castString( visit( ctx.expr() ) );
    }

    /**
     * Generates a subquery to count the number of relationships,
     * optionally constrained to a relationship type by its UID.
     *
     * @param ctx program function parsing context
     * @return subquery to count the number of relationships
     */
    private String relationshipCount( ProgramFunctionContext ctx )
    {
        String relationshipIdConstraint = "";

        if ( ctx.QUOTED_UID() != null )
        {
            String relationshipId = trimQuotes( ctx.QUOTED_UID().getText() );

            relationshipIdConstraint =
                " join relationshiptype rt on r.relationshiptypeid = rt.relationshiptypeid and rt.uid = '"
                    + relationshipId + "'";
        }

        return "(select count(*) from relationship r" + relationshipIdConstraint +
            " join relationshipitem rifrom on rifrom.relationshipid = r.relationshipid" +
            " join trackedentityinstance tei on rifrom.trackedentityinstanceid = tei.trackedentityinstanceid and tei.uid = ax.tei)";
    }

    private String hasValueArg( ColumnContext ctx )
    {
        if ( ctx.programAttribute() != null )
        {
            return statementBuilder.columnQuote( ctx.programAttribute().uid0.getText() );
        }
        else if ( ctx.stageDataElement() != null )
        {
            return statementBuilder.columnQuote( ctx.stageDataElement().uid1.getText() );
        }
        else if ( ctx.uid0 != null )
        {
            return statementBuilder.columnQuote( ctx.uid0.getText() );
        }
        else
        {
            throw new ParserExceptionWithoutContext( "Parser internal error: column context not recognized." );
        }
    }

    /**
     * Generates SQL to count the number of zero or positive values in a list
     * of arguments
     *
     * @param args the list of arguments
     * @return SQL to count the number of zero or positive values
     */
    private String zeroPositiveValueCount( List<String> args )
    {
        String sql = "nullif(cast((";

        for ( String value : args )
        {
            sql += "case when " + value + " >= 0 then 1 else 0 end + ";
        }

        return TextUtils.removeLast( sql, "+" ).trim() + ") as double precision),0)";
    }
}
