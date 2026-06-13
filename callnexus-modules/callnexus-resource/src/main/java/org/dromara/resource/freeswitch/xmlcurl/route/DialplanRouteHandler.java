package org.dromara.resource.freeswitch.xmlcurl.route;

public interface DialplanRouteHandler {

    String routeType();

    String render(DialplanRouteContext context);
}
