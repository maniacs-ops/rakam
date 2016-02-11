package org.rakam.plugin.user;

import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.QualifiedName;
import org.rakam.collection.event.metastore.Metastore;
import org.rakam.plugin.MaterializedView;
import org.rakam.plugin.MaterializedViewService;
import org.rakam.plugin.UserPluginConfig;
import org.rakam.report.PrestoConfig;
import org.rakam.report.PrestoQueryExecutor;
import org.rakam.report.QueryExecutor;
import org.rakam.report.QueryResult;
import org.rakam.report.postgresql.PostgresqlQueryExecutor;

import javax.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.facebook.presto.sql.RakamSqlFormatter.formatExpression;
import static java.lang.String.format;
import static org.rakam.realtime.AggregationType.COUNT;
import static org.rakam.util.ValidationUtil.checkCollection;
import static org.rakam.util.ValidationUtil.checkTableColumn;

public class PrestoExternalUserStorageAdapter extends AbstractPostgresqlUserStorage {
    private final PrestoQueryExecutor executor;
    private final UserPluginConfig config;
    private final MaterializedViewService materializedViewService;
    private final PrestoConfig prestoConfig;

    @Inject
    public PrestoExternalUserStorageAdapter(MaterializedViewService materializedViewService,
                                            PrestoQueryExecutor executor,
                                            PrestoConfig prestoConfig,
                                            UserPluginConfig config,
                                            PostgresqlQueryExecutor queryExecutor,
                                            Metastore metastore) {
        super(queryExecutor);
        this.executor = executor;
        this.config = config;
        this.prestoConfig = prestoConfig;
        this.materializedViewService = materializedViewService;
        queryExecutor.executeRawStatement("CREATE SCHEMA IF NOT EXISTS users").getResult().join();
        metastore.getProjects().forEach(this::createProject);
    }

    @Override
    public CompletableFuture<QueryResult> filter(String project,
                                                 List<String> selectColumns,
                                                 Expression filterExpression,
                                                 List<EventFilter> eventFilter,
                                                 Sorting sortColumn, long limit,
                                                 String offset) {
        if (filterExpression != null && eventFilter != null && !eventFilter.isEmpty()) {
            return super.filter(project, selectColumns, filterExpression, eventFilter, sortColumn, limit, offset);
        }

        String query;
        if (eventFilter != null && !eventFilter.isEmpty()) {
            query = String.format("select distinct _user as %s from (%s) ",
                    config.getIdentifierColumn(),
                    eventFilter.stream().map(f -> String.format(getEventFilterQuery(project, f), executor.formatTableReference(project, QualifiedName.of(f.collection))))
                            .collect(Collectors.joining(" union all ")));
        } else {
            query = String.format("select distinct _user as %s from %s",
                    config.getIdentifierColumn(),
                    executor.formatTableReference(project, QualifiedName.of("_all")));
        }

//        if(sortColumn == null) {
//            sortColumn = new Sorting("_user", Ordering.asc);
//        }

        return executor.executeRawQuery(query +
                (sortColumn == null ? "" : (" ORDER BY " + sortColumn.column + " " + sortColumn.order.name()))
                + " LIMIT " + limit).getResult();
    }

    @Override
    public void createSegment(String project, String name, String tableName, Expression filterExpression, List<EventFilter> eventFilter, Duration interval) {

        String query;
        if (filterExpression == null) {
            query = String.format("select distinct _user as id from (%s)", eventFilter.stream().map(f -> String.format(getEventFilterQuery(project, f), f.collection)).collect(Collectors.joining(" UNION ALL ")));
        } else {
            throw new UnsupportedOperationException();
        }

        materializedViewService.create(new MaterializedView(project, name, tableName, query, interval, null));
    }

    @Override
    public QueryExecutor getExecutorForWithEventFilter() {
        return executor;
    }

    @Override
    public List<String> getEventFilterPredicate(String project, List<EventFilter> eventFilter) {
        return eventFilter.stream().map(f -> String.format("id in (%s)",
                String.format(getEventFilterQuery(project, f), executor.formatTableReference(project, QualifiedName.of(f.collection)))))
                .collect(Collectors.toList());
    }

    public String getEventFilterQuery(String project, EventFilter filter) {
        StringBuilder builder = new StringBuilder();

        checkCollection(filter.collection);

        builder.append("select ")
                .append(config.getEnableUserMapping() ? "coalesce(mapping._user, collection._user, collection.device_id) as _user" : "collection._user")
                .append(" from %s collection");

        ArrayList<String> filterList = new ArrayList<>(3);
        if (filter.filterExpression != null) {
            filterList.add(formatExpression(filter.getExpression(), reference -> executor.formatTableReference(project, reference)));
        }
        if (filter.timeframe != null) {
            if (filter.timeframe.start != null) {
                filterList.add(String.format("_time > cast(%s as timestamp)", filter.timeframe.start.toString()));
            }
            if (filter.timeframe.end != null) {
                filterList.add(String.format("_time < cast(%s as timestamp)", filter.timeframe.end.toString()));
            }
        }

        if (config.getEnableUserMapping()) {
            // and collection.user is not null and mapping.created_at <= max and mapping.merged_at > min
            builder.append(String.format(" left join %s mapping on (mapping.id = collection.device_id)",
                    executor.formatTableReference(project, QualifiedName.of("_anonymous_id_mapping"))));
        }

        if (!filterList.isEmpty()) {
            builder.append(" where ").append(filterList.stream().collect(Collectors.joining(" AND ")));
        }

        if (filter.aggregation != null) {
            String field;
            if (filter.aggregation.type == COUNT && filter.aggregation.field == null) {
                field = "collection._user";
            } else {
                field = "collection.\"" + checkTableColumn(filter.aggregation.field, "aggregation field")+"\"";
            }

            if (config.getEnableUserMapping()) {
                builder.append(" group by mapping._user, collection._user, collection.device_id");
            } else {
                builder.append(" group by collection._user");
            }
            if (filter.aggregation.minimum != null || filter.aggregation.maximum != null) {
                builder.append(" having ");
            }
            if (filter.aggregation.minimum != null) {
                builder.append(format(" %s(%s) >= %d ", filter.aggregation.type, field, filter.aggregation.minimum));
            }
            if (filter.aggregation.maximum != null) {
                if (filter.aggregation.minimum != null) {
                    builder.append(" and ");
                }
                builder.append(format(" %s(%s) < %d ", filter.aggregation.type, field, filter.aggregation.maximum));
            }
        }

        return builder.toString();
    }

    @Override
    public String getUserTable(String project, boolean isEventFilterActive) {
        if (isEventFilterActive) {
            return prestoConfig.getUserConnector() + ".users." + project;
        }

        return "users." + project;
    }
}
