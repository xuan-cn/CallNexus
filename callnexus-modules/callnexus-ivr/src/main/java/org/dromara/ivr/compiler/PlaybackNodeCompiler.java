package org.dromara.ivr.compiler;

import org.springframework.stereotype.Component;

@Component
public class PlaybackNodeCompiler implements IvrNodeCompiler {

    @Override
    public String nodeType() {
        return "PLAYBACK";
    }

    @Override
    public void validate(IvrNodeValidationContext context) {
        context.mediaPathResolver().validatePublishedForGroup(mediaId(context), context.flow().getNodeGroupId());
        context.requireSingleDefaultRoute();
    }

    @Override
    public void compile(IvrNodeContext context) {
        String mediaPath = context.mediaPathResolver().resolveTargetPath(mediaId(context), context.freeSwitchNodeId());
        context.renderSupport().appendNodeStart(context.xml(), context.flow().getId(), context.node());
        context.xml().append("      <action application=\"playback\" data=\"")
            .append(context.renderSupport().escape(mediaPath))
            .append("\"/>\n");
        context.renderSupport().appendTransfer(context.xml(), context.flow().getId(), context.graph().defaultTarget(context.node().id()));
        context.renderSupport().appendNodeEnd(context.xml());
    }

    private Long mediaId(IvrNodeValidationContext context) {
        return context.node().config().path("mediaId").isNumber()
            ? context.node().config().path("mediaId").asLong()
            : parseMediaId(context.node().config().path("mediaId").asText());
    }

    private Long mediaId(IvrNodeContext context) {
        return context.node().config().path("mediaId").isNumber()
            ? context.node().config().path("mediaId").asLong()
            : parseMediaId(context.node().config().path("mediaId").asText());
    }

    private Long parseMediaId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception exception) {
            return null;
        }
    }
}
