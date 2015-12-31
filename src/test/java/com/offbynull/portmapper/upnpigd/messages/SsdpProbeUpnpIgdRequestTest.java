package com.offbynull.portmapper.upnpigd.messages;

import com.offbynull.portmapper.upnpigd.messages.SsdpProbeUpnpIgdRequest.ProbeDeviceType;
import org.junit.Assert;
import org.junit.Test;

public class SsdpProbeUpnpIgdRequestTest {

    public SsdpProbeUpnpIgdRequestTest() {
    }

    @Test
    public void mustProperlyGenerateIpv4Request() throws Exception {
        SsdpProbeUpnpIgdRequest req = new SsdpProbeUpnpIgdRequest(ProbeDeviceType.IPV4, 3, 4, "fake:service");
        String bufferText = new String(req.dump(), "US-ASCII");

        Assert.assertEquals(
                "M-SEARCH * HTTP/1.1\r\n"
                + "MM: 3\r\n"
                + "MX: 4\r\n"
                + "ST: fake:service\r\n"
                + "MAN: ssdp:discover\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "\r\n",
                bufferText);
    }

    @Test
    public void mustProperlyGenerateLinkLocalIpv6Request() throws Exception {
        SsdpProbeUpnpIgdRequest req = new SsdpProbeUpnpIgdRequest(ProbeDeviceType.IPV6_LINK_LOCAL, 3, 4, "fake:service");
        String bufferText = new String(req.dump(), "US-ASCII");

        Assert.assertEquals(
                "M-SEARCH * HTTP/1.1\r\n"
                + "MM: 3\r\n"
                + "MX: 4\r\n"
                + "ST: fake:service\r\n"
                + "MAN: ssdp:discover\r\n"
                + "HOST: [FF02::C]:1900\r\n"
                + "\r\n",
                bufferText);
    }

    @Test
    public void mustProperlyGenerateSiteLocalIpv6Request() throws Exception {
        SsdpProbeUpnpIgdRequest req = new SsdpProbeUpnpIgdRequest(ProbeDeviceType.IPV6_SITE_LOCAL, 3, 4, "fake:service");
        String bufferText = new String(req.dump(), "US-ASCII");

        Assert.assertEquals(
                "M-SEARCH * HTTP/1.1\r\n"
                + "MM: 3\r\n"
                + "MX: 4\r\n"
                + "ST: fake:service\r\n"
                + "MAN: ssdp:discover\r\n"
                + "HOST: [FF05::C]:1900\r\n"
                + "\r\n",
                bufferText);
    }

    @Test
    public void mustProperlyGenerateRequestWhenMmIsMissing() throws Exception {
        SsdpProbeUpnpIgdRequest req = new SsdpProbeUpnpIgdRequest(ProbeDeviceType.IPV4, null, 4, "fake:service");
        String bufferText = new String(req.dump(), "US-ASCII");

        Assert.assertEquals(
                "M-SEARCH * HTTP/1.1\r\n"
                + "MX: 4\r\n"
                + "ST: fake:service\r\n"
                + "MAN: ssdp:discover\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "\r\n",
                bufferText);
    }

    @Test(expected=IllegalArgumentException.class)
    public void mustFailToGenerateRequestWhenMmIsGreaterThanMx() throws Exception {
        SsdpProbeUpnpIgdRequest req = new SsdpProbeUpnpIgdRequest(ProbeDeviceType.IPV4, 4, 3, "fake:service");
    }

}
