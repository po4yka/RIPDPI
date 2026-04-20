internal object DiagnosticsCatalogDpiData {
    val domainTargets =
        domainTargets(
            """
            www.instagram.com
            www.facebook.com
            x.com
            www.linkedin.com
            discord.com
            gateway.discord.gg
            media.discordapp.net
            www.youtube.com
            soundcloud.com
            proton.me
            hub.docker.com
            www.intel.com
            www.canva.com
            www.coursera.org
            aws.amazon.com
            www.apkmirror.com
            www.messenger.com
            www.linuxserver.io
            danbooru.donmai.us
            gelbooru.com
            browserleaks.com
            www.cdn77.com
            vk.ru
            www.google.com
            """.trimIndent(),
        )

    val tcpTargets =
        tcpTargets(
            """
            HE-01|Hetzner|91.98.156.82|443|24940
            HE-02|Hetzner|46.62.154.37|443|24940
            HE-02|Hetzner HTTP|185.189.229.124|80|24940
            HE-03|Hetzner Cloud 2|5.161.249.234|443|213230
            HE-04|Hetzner Cloud 2|178.156.198.203|443|213230
            HE-05|Hetzner Cloud 3|5.78.155.139|443|212317
            HE-06|Hetzner Cloud 3|5.78.82.109|443|212317
            HE-07|Hetzner Cloud 4|5.223.45.178|443|215859
            HE-08|Hetzner Cloud 4|5.223.68.131|443|215859
            CF-01|Cloudflare|172.67.70.222|443|13335
            CF-02|Cloudflare|172.67.181.54|443|13335
            CF-03|Cloudflare|104.18.63.125|443|13335
            CF-04|Cloudflare|104.19.153.39|443|13335
            CF-05|Cloudflare|104.18.46.6|443|13335
            CF-06|Cloudflare|104.16.59.56|443|13335
            CDN-02|CDN77 #1|89.187.169.9|443|60068
            CDN-03|CDN77 #1|169.150.239.5|443|60068
            CDN-04|CDN77 #1 HTTP|84.17.50.100|80|60068
            CDN-05|CDN77 #2|82.27.130.121|443|212238
            CDN-06|CDN77 #2|194.32.146.68|443|212238
            CDN-07|CDN77 #2|173.211.70.143|443|212238
            AK-01|Akamai 1|23.222.76.4|443|20940
            AK-02|Akamai 1|23.46.144.6|443|20940
            AK-03|Akamai 1 HTTP|72.246.41.5|80|20940
            AK-04|Akamai 1|95.100.240.7|443|20940
            AK-05|Akamai 2|23.1.64.4|443|16625
            AK-06|Akamai 2|184.31.208.5|443|16625
            AK-07|Akamai 3|23.62.225.111|443|12222
            AK-08|Akamai 3|88.221.209.114|443|12222
            AK-09|Akamai 4|23.11.41.163|443|33905
            AK-10|Akamai Cloud|172.236.2.30|443|63949
            AK-11|Akamai Cloud|212.71.233.10|443|63949
            AK-10|Akamai Cloud|172.236.2.30|443|63949
            AK-11|Akamai Cloud|212.71.233.10|443|63949
            GOOG-01|Google DNS|8.8.8.8|443|15169
            AWS-02|AWS 1|76.223.67.41|443|16509
            AWS-03|AWS 1|168.100.27.4|443|16509
            AWS-04|AWS 2|54.196.100.238|443|14618
            AWS-05|AWS 2|148.5.72.176|443|14618
            AWS-06|AWS 3|198.22.163.54|443|8987
            AWS-07|AWS 3|108.175.48.27|443|8987
            CNT-01|Contabo 1|173.249.28.198|443|51167
            CNT-02|Contabo 1|62.171.173.1|443|51167
            CNT-03|Contabo 1|185.211.6.51|443|51167
            CNT-04|Contabo 1|5.189.159.58|443|51167
            CNT-05|Contabo 2|217.217.252.183|443|141995
            CNT-06|Contabo 2|147.93.153.44|443|141995
            CNT-07|Contabo 2|94.136.190.9|443|141995
            FST-01|Fastly|151.101.66.245|443|54113
            FST-02|Fastly|151.101.194.114|443|54113
            Q9-01|Quad9|9.9.9.9|443|19281
            GC-02|Google Cloud|35.227.66.60|443|396982
            GCR-01|Gcore 1|213.156.152.155|443|199524
            GCR-02|Gcore 1|92.223.13.10|443|199524
            GCR-03|Gcore 1|185.145.113.133|443|199524
            GCR-04|Gcore 1|82.117.228.93|443|199524
            GCR-05|Gcore 2|5.8.34.27|443|202422
            GCR-06|Gcore 2|5.188.190.167|443|202422
            OR-01|Oracle Cloud|152.67.64.161|443|31898
            OR-02|Oracle Cloud|67.222.57.149|443|31898
            OR-03|Oracle Cloud|132.145.253.77|443|31898
            OR-04|Oracle Cloud|158.180.238.77|443|31898
            OR-05|Oracle 1 HTTP|79.72.37.28|80|54253
            OR-06|Oracle 1|158.178.160.240|443|54253
            OR-07|Oracle 1|217.142.192.157|443|54253
            OR-09|Oracle 2|156.151.59.19|443|1219
            OR-10|Oracle 3|139.87.83.30|443|6142
            OR-11|Oracle 3|139.87.117.158|443|6142
            OR-12|Oracle 4|207.127.85.89|443|14544
            OR-13|Oracle 5|155.248.72.27|443|20054
            OVH-01|OVH|199.168.193.103|443|16276
            OVH-02|OVH|141.95.228.164|443|16276
            OVH-03|OVH|46.105.222.91|443|16276
            OVH-04|OVH|40.160.45.210|443|16276
            OVH-05|OVH HTTP|185.155.218.223|80|16276
            SW-01|Scaleway 1|62.4.10.75|443|12876
            SW-02|Scaleway 1|51.158.153.203|443|12876
            SW-03|Scaleway 1|212.83.152.108|443|12876
            SW-04|Scaleway 1 HTTP|163.172.223.99|80|12876
            SW-05|Scaleway 2|81.56.227.171|443|29447
            SW-06|Scaleway 2|83.158.240.200|443|29447
            SW-07|Scaleway 2|81.56.11.201|443|29447
            DO-01|DigitalOcean 1|157.230.91.62|443|14061
            DO-02|DigitalOcean 1|138.68.63.162|443|14061
            DO-03|DigitalOcean 1|159.203.180.157|443|14061
            DO-04|DigitalOcean 1|147.182.168.41|443|14061
            DO-05|DigitalOcean 1|45.55.107.70|443|14061
            DO-06|DigitalOcean 2|141.0.173.188|443|46652
            DO-07|DigitalOcean 2|69.55.57.11|443|46652
            VLT-01|Vultr|209.250.243.50|443|20473
            VLT-02|Vultr|64.176.188.124|443|20473
            VLT-03|Vultr|108.61.167.59|443|20473
            VLT-04|Vultr|173.199.115.182|443|20473
            MBC-01|Melbicom 1|193.35.224.194|443|8849
            MBC-03|Melbicom 2|185.6.14.1|443|56630
            FT-01|FranTech|104.244.74.134|443|53667
            FT-02|FranTech|198.251.88.98|443|53667
            CV-01|Clouvider|130.185.249.20|443|62240
            CV-02|Clouvider|194.127.178.154|443|62240
            ME-01|M247 Europe SRL|146.70.158.22|443|9009
            IM-01|IOMART 1|213.175.200.210|443|20860
            IM-02|IOMART 2|185.224.199.82|443|21130
            ZL-01|Zenlayer|193.118.45.137|443|21859
            CN-01|CreaNova|185.212.149.82|443|51765
            SCA-01|Scalaxy|5.61.55.146|443|58061
            """.trimIndent(),
        )

    val whitelistSni =
        lines(
            """
            vk.com
            2gis.com
            2gis.ru
            300.ya.ru
            ad.adriver.ru
            3475482542.mc.yandex.ru
            742231.ms.ok.ru
            a.wb.ru
            ad.mail.ru
            adm.mp.rzd.ru
            akashi.vk-portal.net
            alfabank.ru
            web.max.ru
            ams2-cdn.2gis.com
            an.yandex.ru
            api-maps.yandex.ru
            api.2gis.ru
            api.avito.ru
            api.browser.yandex.com
            api.browser.yandex.ru
            api.events.plus.yandex.net
            api.mindbox.ru
            api.photo.2gis.com
            api.reviews.2gis.com
            avatars.mds.yandex.com
            avatars.mds.yandex.net
            avito.ru
            banners-website.wildberries.ru
            bot.gosuslugi.ru
            bro-bg-store.s3.yandex.com
            bro-bg-store.s3.yandex.net
            bro-bg-store.s3.yandex.ru
            brontp-pre.yandex.ru
            browser.yandex.com
            browser.yandex.ru
            cargo.rzd.ru
            catalog.api.2gis.com
            cdn.lemanapro.ru
            cdn.yandex.ru
            cdnrhkgfkkpupuotntfj.svc.cdn.yandex.net
            chat-prod.wildberries.ru
            chat3.vtb.ru
            cloud.cdn.yandex.com
            cloud.cdn.yandex.net
            cloud.cdn.yandex.ru
            cloudcdn-ams19.cdn.yandex.net
            collections.yandex.com
            collections.yandex.ru
            company.rzd.ru
            contacts.rzd.ru
            contract.gosuslugi.ru
            cs.avito.ru
            csp.yandex.net
            d-assets.2gis.ru
            disk.2gis.com
            disk.rzd.ru
            dmp.dmpkit.lemanapro.ru
            dr.yandex.net
            dr2.yandex.net
            dzen.ru
            egress.yandex.net
            eh.vk.com
            ekmp-a-51.rzd.ru
            enterprise.api-maps.yandex.ru
            esia.gosuslugi.ru
            favorites.api.2gis.com
            favicon.yandex.com
            favicon.yandex.net
            favicon.yandex.ru
            filekeeper-vod.2gis.com
            frontend.vh.yandex.ru
            gosuslugi.ru
            i0.photo.2gis.com
            i1.photo.2gis.com
            i2.photo.2gis.com
            i3.photo.2gis.com
            i4.photo.2gis.com
            i5.photo.2gis.com
            i6.photo.2gis.com
            i7.photo.2gis.com
            i8.photo.2gis.com
            i9.photo.2gis.com
            id.sber.ru
            jam.api.2gis.com
            keys.api.2gis.com
            kiks.yandex.com
            kiks.yandex.ru
            lemanapro.ru
            link.mp.rzd.ru
            lk.gosuslugi.ru
            log.strm.yandex.ru
            login.vk.com
            m.avito.ru
            mail.yandex.com
            mail.yandex.ru
            map.gosuslugi.ru
            mapgl.2gis.com
            market.rzd.ru
            mc.yandex.com
            mc.yandex.ru
            mddc.tinkoff.ru
            mediafeeds.yandex.com
            mediafeeds.yandex.ru
            metrics.alfabank.ru
            mp.rzd.ru
            my.rzd.ru
            neuro.translate.yandex.ru
            novorossiya.gosuslugi.ru
            ok.ru
            ozon.ru
            partners.gosuslugi.ru
            partners.lemanapro.ru
            personalization-web-stable.mindbox.ru
            pos.gosuslugi.ru
            privacy-cs.mail.ru
            prodvizhenie.rzd.ru
            public-api.reviews.2gis.com
            pulse.mp.rzd.ru
            queuev4.vk.com
            rs.mail.ru
            rzd.ru
            s.vtb.ru
            s0.bss.2gis.com
            s1.bss.2gis.com
            s3.yandex.net
            sba.yandex.com
            sba.yandex.net
            sba.yandex.ru
            secure-cloud.rzd.ru
            secure.rzd.ru
            sfd.gosuslugi.ru
            sntr.avito.ru
            speller.yandex.net
            splitter.wb.ru
            sso-app4.vtb.ru
            sso-app5.vtb.ru
            sso.dzen.ru
            st-ok.cdn-vk.ru
            st.avito.ru
            static-mon.yandex.net
            static.lemanapro.ru
            stats.avito.ru
            stats.vk-portal.net
            storage.ape.yandex.net
            strm-rad-23.strm.yandex.net
            strm-spbmiran-08.strm.yandex.net
            strm.yandex.net
            strm.yandex.ru
            styles.api.2gis.com
            suggest.dzen.ru
            suggest.sso.dzen.ru
            surveys.yandex.ru
            sync.browser.yandex.net
            team.rzd.ru
            ticket.rzd.ru
            tile0.maps.2gis.com
            tile1.maps.2gis.com
            tile2.maps.2gis.com
            tile3.maps.2gis.com
            tile4.maps.2gis.com
            top-fwz1.mail.ru
            travel.rzd.ru
            user-geo-data.wildberries.ru
            vk-portal.net
            wap.yandex.com
            wap.yandex.ru
            wb.ru
            web-static.mindbox.ru
            welcome.rzd.ru
            widgets.cbonds.ru
            www.avito.ru
            www.gosuslugi.ru
            www.ozon.ru
            www.rzd.ru
            www.vtb.ru
            www.wildberries.ru
            xapi.ozon.ru
            yabro-wbplugin.edadeal.yandex.ru
            yabs.yandex.ru
            yandex.com
            yandex.net
            yandex.ru
            yastatic.net
            zen-yabro-morda.mediascope.mc.yandex.ru
            zen.yandex.com
            zen.yandex.net
            zen.yandex.ru
            """.trimIndent(),
        )
}
