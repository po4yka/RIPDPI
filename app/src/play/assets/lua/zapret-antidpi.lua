-- Standard Lua strategies for existing sockets.
-- Selected and adapted from zapret2 (MIT, see LICENSE.zapret2.txt).

function drop(ctx, desync)
	direction_cutoff_opposite(ctx, desync, "any")
	if direction_check(desync, "any") and payload_check(desync,"all") then
		DLOG("drop")
		return VERDICT_DROP
	end
end

function http_domcase(ctx, desync)
	if not desync.dis.tcp then
		-- do not cutoff on related icmp
		if not desync.dis.icmp then instance_cutoff_shim(ctx, desync) end
		return
	end
	direction_cutoff_opposite(ctx, desync)
	if desync.l7payload=="http_req" and direction_check(desync) then
		local host_range = resolve_multi_pos(desync.dis.payload,desync.l7payload,"host,endhost")
		if #host_range == 2 then
			local host = string.sub(desync.dis.payload,host_range[1],host_range[2]-1)
			local newhost=""
			for i = 1, #host do
				newhost=newhost..((i%2)==0 and string.lower(string.sub(host,i,i)) or string.upper(string.sub(host,i,i)))
			end
			DLOG("http_domcase: "..host.." => "..newhost)
			desync.dis.payload = string.sub(desync.dis.payload, 1, host_range[1]-1)..newhost..string.sub(desync.dis.payload, host_range[2])
			return VERDICT_MODIFY
		else
			DLOG("http_domcase: cannot find host range")
		end
	end
end

function http_hostcase(ctx, desync)
	if not desync.dis.tcp then
		-- do not cutoff on related icmp
		if not desync.dis.icmp then instance_cutoff_shim(ctx, desync) end
		return
	end
	direction_cutoff_opposite(ctx, desync)
	if desync.l7payload=="http_req" and direction_check(desync) then
		local spell = desync.arg.spell or "host"
		if #spell ~= 4 then
			error("http_hostcase: invalid host spelling '"..spell.."'")
		else
			local hdis = http_dissect_req(desync.dis.payload)
			if hdis then
				local idx_host = array_field_search(hdis.headers, "header_low", "host")
				if idx_host then
					DLOG("http_hostcase: 'Host:' => '"..spell.."'")
					desync.dis.payload = string.sub(desync.dis.payload,1,hdis.headers[idx_host].pos_start-1)..spell..string.sub(desync.dis.payload,hdis.headers[idx_host].pos_header_end+1)
					return VERDICT_MODIFY
				else
					DLOG("http_hostcase: 'Host:' header not found")
				end
			else
				DLOG("http_hostcase: http dissect error")
			end
		end
	end
end

function http_methodeol(ctx, desync)
	if not desync.dis.tcp then
		-- do not cutoff on related icmp
		if not desync.dis.icmp then instance_cutoff_shim(ctx, desync) end
		return
	end
	direction_cutoff_opposite(ctx, desync)
	if desync.l7payload=="http_req" and direction_check(desync) then
		local hdis = http_dissect_req(desync.dis.payload)
		if hdis then
			local idx_ua = array_field_search(hdis.headers, "header_low", "user-agent")
			if idx_ua then
				local ua = hdis.headers[idx_ua]
				if (ua.pos_end - ua.pos_value_start) < 2 then
					DLOG("http_methodeol: 'User-Agent:' header is too short")
				else
					DLOG("http_methodeol: applied")
					desync.dis.payload="\r\n"..string.sub(desync.dis.payload,1,ua.pos_end-2)..(string.sub(desync.dis.payload,ua.pos_end+1) or "");
					return VERDICT_MODIFY
				end
			else
				DLOG("http_methodeol: 'User-Agent:' header not found")
			end
		else
			DLOG("http_methodeol: http dissect error")
		end
	end
end

function http_unixeol(ctx, desync)
	if not desync.dis.tcp then
		-- do not cutoff on related icmp
		if not desync.dis.icmp then instance_cutoff_shim(ctx, desync) end
		return
	end
	direction_cutoff_opposite(ctx, desync)
	if desync.l7payload=="http_req" and direction_check(desync) then
		local hdis = http_dissect_req(desync.dis.payload)
		if hdis and hdis.pos_headers_end then
			local idx_ua = array_field_search(hdis.headers, "header_low", "user-agent")
			if idx_ua then
				local http = http_reconstruct_req(hdis, true)
				if #http < #desync.dis.payload then
					hdis.headers[idx_ua].value = hdis.headers[idx_ua].value .. string.rep(" ", #desync.dis.payload - #http)
				end
				http = http_reconstruct_req(hdis, true)
				if #http==#desync.dis.payload then
					desync.dis.payload = http
					DLOG("http_unixeol: applied")
					return VERDICT_MODIFY
				else
					DLOG("http_unixeol: reconstruct differs in size from original: "..#http.."!="..#desync.dis.payload)
				end
			else
				DLOG("http_unixeol: 'User-Agent:' header absent")
			end
		else
			DLOG("http_unixeol: could not dissect http")
		end
	end
end

function udplen(ctx, desync)
	if not desync.dis.udp then
		-- do not cutoff on related icmp
		if not desync.dis.icmp then instance_cutoff_shim(ctx, desync) end
		return
	end
	direction_cutoff_opposite(ctx, desync)
	if direction_check(desync) and payload_check(desync) then
		local len = #desync.dis.payload
		if (desync.arg.min and #desync.dis.payload < tonumber(desync.arg.min)) then
			DLOG("udplen: payload size "..len.." is less than the minimum size "..desync.arg.min)
		elseif (desync.arg.max and #desync.dis.payload > tonumber(desync.arg.max)) then
			DLOG("udplen: payload size "..len.." is more than the maximum size "..desync.arg.max)
		else
			local inc = desync.arg.increment and tonumber(desync.arg.increment) or 2
			if inc>0 then
				local pat = desync.arg.pattern and blob(desync,desync.arg.pattern) or "\x00"
				local pat_offset = desync.arg.pattern_offset and (tonumber(desync.arg.pattern_offset)+1) or 1
				desync.dis.payload = desync.dis.payload .. pattern(pat, pat_offset, inc)
				DLOG("udplen: "..len.." => "..#desync.dis.payload)
				return VERDICT_MODIFY
			elseif inc<0 then
				if (len+inc)<1 then
					DLOG("udplen: will not shrink to zero length")
				else
					desync.dis.payload = string.sub(desync.dis.payload,1,len+inc)
					DLOG("udplen: "..len.." => "..#desync.dis.payload)
					return VERDICT_MODIFY
				end
			end
		end
	end
end

function dht_dn(ctx, desync)
	if not desync.dis.udp then
		-- do not cutoff on related icmp
		if not desync.dis.icmp then instance_cutoff_shim(ctx, desync) end
		return
	end
	direction_cutoff_opposite(ctx, desync)
	if desync.l7payload=="dht" and direction_check(desync) then
		local N = tonumber(desync.arg.dn) or 3
		-- remove "d1" from the start not breaking bencode
		local prefix = "d"..tostring(N)..":"..string.rep("0",N).."1:x"
		desync.dis.payload = prefix..string.sub(desync.dis.payload,2)
		DLOG("dht_dn: tampered dht to start with '"..prefix.."' instead of 'd1:'")
		return VERDICT_MODIFY
	end
end

local function socket_payload(desync)
	local arg = desync.arg
	if arg.optional and arg.blob and not blob_exist(desync, arg.blob) then return end
	local payload = blob_or_def(desync, arg.blob) or desync.dis.payload
	if #payload > 0 and direction_check(desync) and payload_check(desync) then
		return payload
	end
end

local function write_segments(desync, payload, boundaries)
	-- The socket API accepts original TCP bytes in their original order.
	-- Options for modifying packet headers or overlapping sequence ranges are unavailable.
	local arg = desync.arg
	if arg.repeats or arg.ifout or arg.fwmark or arg.badsum or arg.ipfrag
		or (arg.seqovl and tonumber(arg.seqovl) > 0) then
		error("TCP segmentation requires an unchanged payload and headers")
	end
	local segment = deepcopy(desync.dis)
	for i = 1, #boundaries - 1 do
		local last = boundaries[i + 1] - 1
		for first = boundaries[i], last, desync.tcp_mss do
			segment.payload = string.sub(payload, first, math.min(last, first + desync.tcp_mss - 1))
			segment.tcp.th_seq = first - 1
			desync.write_segment(segment)
		end
	end
end

function multisplit(ctx, desync)
	if not desync.dis.tcp then
		if not desync.dis.icmp then instance_cutoff_shim(ctx, desync) end
		return
	end
	direction_cutoff_opposite(ctx, desync)
	local payload = socket_payload(desync)
	if not payload then return end
	local pos = resolve_multi_pos(payload, desync.l7payload, desync.arg.pos or "2")
	delete_pos_1(pos)
	if #pos == 0 then return end
	table.insert(pos, 1, 1)
	pos[#pos + 1] = #payload + 1
	write_segments(desync, payload, pos)
	return desync.arg.nodrop and VERDICT_PASS or VERDICT_DROP
end

function tcpseg(ctx, desync)
	if not desync.dis.tcp then
		if not desync.dis.icmp then instance_cutoff_shim(ctx, desync) end
		return
	end
	direction_cutoff_opposite(ctx, desync)
	if not desync.arg.pos then error("tcpseg: no pos specified") end
	local payload = socket_payload(desync)
	if not payload then return end
	local pos = resolve_range(payload, desync.l7payload, desync.arg.pos)
	if pos then
		pos[2] = pos[2] + 1
		write_segments(desync, payload, pos)
	end
end
