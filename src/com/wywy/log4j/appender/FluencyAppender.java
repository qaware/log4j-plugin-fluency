package com.wywy.log4j.appender;


import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.pattern.NameAbbreviator;
import org.apache.logging.log4j.core.util.Booleans;
import org.apache.logging.log4j.status.StatusLogger;
import org.komamitsu.fluency.Fluency;

import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Plugin(name="Fluency", category="Core", elementType="appender", printObject=true)
public final class FluencyAppender extends AbstractAppender {

    private static final StatusLogger LOG = StatusLogger.getLogger();
    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private final NameAbbreviator abbr = NameAbbreviator.getAbbreviator("1.");

    private Fluency fluency;
    private Map<String, Object> parameters;
    private Map<String, String> staticFields;

    private FluencyAppender(final String name, final Map<String, Object> parameters, final Map<String, String> staticFields,
                            final Filter filter, final Layout<? extends Serializable> layout,
                            final boolean ignoreExceptions) {

        super(name, filter, layout, ignoreExceptions);

        this.parameters = parameters;
        this.staticFields = staticFields;

        // Single Fluentd(localhost:24224 by default)
        //   - TCP heartbeat (by default)
        //   - Asynchronous flush (by default)
        //   - Without ack response (by default)
        //   - Flush interval is 600ms (by default)
        //   - Initial chunk buffer size is 1MB (by default)
        //   - Threshold chunk buffer size to flush is 4MB (by default)
        //   - Max total buffer size is 16MB (by default)
        //   - Max retry of sending events is 8 (by default)
        //   - Max wait until all buffers are flushed is 10 seconds (by default)
        //   - Max wait until the flusher is terminated is 10 seconds (by default)
        try {
            this.fluency = Fluency.defaultFluency();
            LOG.info("FluencyAppender initialized");
        } catch (IOException e) {
            LOG.error(e.getMessage());
        }
    }

    @PluginFactory
    public static FluencyAppender createAppender(@PluginAttribute("name") final String name,
                                                 @PluginAttribute("tag") final String tag,
                                                 @PluginAttribute("application") final String application,
                                                 @PluginAttribute("ignoreExceptions") final String ignore,
                                                 @PluginElement("StaticField") final StaticField[] staticFields,
                                                 @PluginElement("Layout") Layout<? extends Serializable> layout,
                                                 @PluginElement("Filter") final Filter filter) {
        final boolean ignoreExceptions = Booleans.parseBoolean(ignore, true);

        Map<String, Object> parameters = new HashMap<>();
        Map<String, String> fields = new HashMap<>();

        // the @Required annotation for Attributes just returns a non helpful exception, so i'll check it manually
        // we need the tag for fluency itself (it's actually a part of the fluentd protocol
        if (tag != null) {
            parameters.put("tag", tag);
        } else {
            throw new IllegalArgumentException("tag is required");
        }

        // Deprecated
        if (application != null) {
            fields.put("application", application);
        }

        for (StaticField field: staticFields) {
            if (field.getName().trim().equals("")) {
                LOG.warn("Skipping empty field");
                continue;
            }
            if (field.getValue().trim().equals("")) {
                LOG.warn("Skipping field {} due to empty value", field.getName());
                continue;
            }
            fields.put(field.getName(), field.getValue());
        }

        return new FluencyAppender(name, parameters, fields, filter, layout, ignoreExceptions);
    }

    @Override
    public void append(LogEvent logEvent) {
        String level = logEvent.getLevel().name();
        String loggerName = logEvent.getLoggerName();
        String message = logEvent.getMessage().getFormattedMessage();
        Date eventTime = new Date(logEvent.getTimeMillis());

        Map<String, Object> m = new HashMap<>();
        m.put("level", level);

        StackTraceElement logSource = logEvent.getSource();

        if (logSource == null || logSource.getFileName() == null) {
            m.put("sourceFile", "<unknown>");
        } else {
            m.put("sourceFile", logSource.getFileName());
        }

        if (logSource == null || logSource.getClassName() == null) {
            m.put("sourceClass", "<unknown>");
        } else {
            m.put("sourceClass", logEvent.getSource().getClassName());
        }

        if (logSource == null || logSource.getMethodName() == null) {
            m.put("sourceMethod", "<unknown>");
        } else {
            m.put("sourceMethod", logEvent.getSource().getMethodName());
        }

        if (logSource == null || logSource.getLineNumber() == 0) {
            m.put("sourceLine", 0);
        } else {
            m.put("sourceLine", logEvent.getSource().getLineNumber());
        }

        m.put("logger", abbr.abbreviate(loggerName));
        m.put("loggerFull", loggerName);
        m.put("message", message);
        m.put("thread", logEvent.getThreadName());
        m.putAll(this.staticFields);

        // TODO: get rid of that, once the whole stack supports subsecond timestamps
        // this is just a workaround due to the lack of support
        m.put("@timestamp", format.format(eventTime));

        if (this.fluency != null) {
            try {
                // the tag is required for further processing within fluentd,
                // otherwise we would have no way to manipulate messages in transit
                this.fluency.emit((String) parameters.get("tag"), logEvent.getTimeMillis(), m);
            } catch (IOException e) {
                LOG.error(e.getMessage());
            }
        }
    }

}
