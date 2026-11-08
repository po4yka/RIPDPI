use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;

use ripdpi_flow_app_attribution::{FlowRegistrationId, FlowResolveRequest, FlowUidLookup};

use super::UdpAssociation;

#[derive(Clone, PartialEq, Eq, Hash)]
pub(in crate::io_loop) struct UdpAssociationKey {
    pub(in crate::io_loop::udp_assoc) src: SocketAddr,
    pub(in crate::io_loop::udp_assoc) app_scope: Option<Arc<str>>,
    owner_uid: Option<u32>,
}

impl UdpAssociationKey {
    pub(in crate::io_loop::udp_assoc) fn for_registration(src: SocketAddr, id: &FlowRegistrationId) -> Self {
        let app_scope = ripdpi_flow_app_attribution::lookup_registered_app_scope(id);
        let owner_uid = if app_scope.is_none() {
            match ripdpi_flow_app_attribution::lookup_registered_flow_uid(id) {
                FlowUidLookup::Resolved(uid) => uid,
                FlowUidLookup::Missing | FlowUidLookup::Pending => None,
            }
        } else {
            None
        };
        Self { src, app_scope, owner_uid }
    }
}

impl From<SocketAddr> for UdpAssociationKey {
    fn from(src: SocketAddr) -> Self {
        Self { src, app_scope: None, owner_uid: None }
    }
}

impl From<&SocketAddr> for UdpAssociationKey {
    fn from(src: &SocketAddr) -> Self {
        Self::from(*src)
    }
}

impl From<&UdpAssociationKey> for UdpAssociationKey {
    fn from(key: &UdpAssociationKey) -> Self {
        key.clone()
    }
}

/// Owner-scoped transports and their bounded source, worker and destination indexes.
#[derive(Default)]
pub(in crate::io_loop) struct UdpAssociations {
    entries: HashMap<UdpAssociationKey, UdpAssociation>,
    by_id: HashMap<u64, UdpAssociationKey>,
    source_counts: HashMap<SocketAddr, usize>,
    destinations: HashMap<FlowResolveRequest, (FlowRegistrationId, u64)>,
}

impl UdpAssociations {
    pub(in crate::io_loop) fn new() -> Self {
        Self::default()
    }

    pub(in crate::io_loop) fn len(&self) -> usize {
        self.entries.len()
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub(in crate::io_loop) fn contains_key(&self, src: &SocketAddr) -> bool {
        self.source_counts.contains_key(src)
    }

    pub(in crate::io_loop::udp_assoc) fn get(&self, key: impl Into<UdpAssociationKey>) -> Option<&UdpAssociation> {
        self.entries.get(&key.into())
    }

    pub(in crate::io_loop::udp_assoc) fn get_mut(
        &mut self,
        key: impl Into<UdpAssociationKey>,
    ) -> Option<&mut UdpAssociation> {
        self.entries.get_mut(&key.into())
    }

    pub(in crate::io_loop::udp_assoc) fn key_by_id(&self, id: u64) -> Option<&UdpAssociationKey> {
        self.by_id.get(&id)
    }

    pub(in crate::io_loop::udp_assoc) fn get_by_id(&self, id: u64) -> Option<&UdpAssociation> {
        self.entries.get(self.by_id.get(&id)?)
    }

    pub(in crate::io_loop::udp_assoc) fn get_by_id_mut(&mut self, id: u64) -> Option<&mut UdpAssociation> {
        self.entries.get_mut(self.by_id.get(&id)?)
    }

    pub(in crate::io_loop::udp_assoc) fn registration_for(
        &self,
        request: &FlowResolveRequest,
    ) -> Option<(FlowRegistrationId, &UdpAssociationKey)> {
        let (registration, id) = self.destinations.get(request)?;
        Some((*registration, self.by_id.get(id)?))
    }

    pub(in crate::io_loop) fn get_registration_mut(
        &mut self,
        registration: &FlowRegistrationId,
    ) -> Option<&mut UdpAssociation> {
        let (current, id) = *self.destinations.get(&registration.request())?;
        if current != *registration {
            return None;
        }
        self.get_by_id_mut(id)
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn test_destination_snapshot(
        &self,
        registration: &FlowRegistrationId,
    ) -> Option<(u64, Arc<super::UdpDestination>)> {
        let (current, id) = *self.destinations.get(&registration.request())?;
        if current != *registration {
            return None;
        }
        let destination = self.get_by_id(id)?.destinations.get(&registration.request().remote)?;
        Some((id, Arc::clone(destination)))
    }

    pub(in crate::io_loop::udp_assoc) fn owns_registration(&self, registration: &FlowRegistrationId) -> bool {
        self.destinations
            .get(&registration.request())
            .is_some_and(|(current, id)| current == registration && self.by_id.contains_key(id))
    }

    pub(in crate::io_loop::udp_assoc) fn record_destination(
        &mut self,
        key: &UdpAssociationKey,
        registration: FlowRegistrationId,
    ) {
        if let Some(association) = self.entries.get(key) {
            self.destinations.insert(registration.request(), (registration, association.id));
        }
    }

    pub(in crate::io_loop::udp_assoc) fn forget_destination(
        &mut self,
        request: &FlowResolveRequest,
        association_id: u64,
    ) {
        if self.destinations.get(request).is_some_and(|(_, id)| *id == association_id) {
            self.destinations.remove(request);
        }
    }

    pub(in crate::io_loop::udp_assoc) fn insert(
        &mut self,
        key: impl Into<UdpAssociationKey>,
        association: UdpAssociation,
    ) -> Option<UdpAssociation> {
        let key = key.into();
        let previous = self.remove(&key);
        self.by_id.insert(association.id, key.clone());
        *self.source_counts.entry(key.src).or_default() += 1;
        for (request, registration) in &association.attribution_ids {
            if association.destinations.contains_key(&request.remote) {
                self.destinations.insert(*request, (*registration, association.id));
            }
        }
        self.entries.insert(key, association);
        previous
    }

    pub(in crate::io_loop::udp_assoc) fn remove(
        &mut self,
        key: impl Into<UdpAssociationKey>,
    ) -> Option<UdpAssociation> {
        let key = key.into();
        let association = self.entries.remove(&key)?;
        if self.by_id.get(&association.id) == Some(&key) {
            self.by_id.remove(&association.id);
        }
        if let Some(count) = self.source_counts.get_mut(&key.src) {
            *count -= 1;
            if *count == 0 {
                self.source_counts.remove(&key.src);
            }
        }
        for &remote in association.destinations.keys() {
            self.forget_destination(&FlowResolveRequest { protocol: 17, local: key.src, remote }, association.id);
        }
        Some(association)
    }

    pub(in crate::io_loop::udp_assoc) fn iter(&self) -> impl Iterator<Item = (&UdpAssociationKey, &UdpAssociation)> {
        self.entries.iter()
    }

    pub(in crate::io_loop::udp_assoc) fn iter_mut(
        &mut self,
    ) -> impl Iterator<Item = (&UdpAssociationKey, &mut UdpAssociation)> {
        self.entries.iter_mut()
    }

    pub(in crate::io_loop::udp_assoc) fn drain(
        &mut self,
    ) -> impl Iterator<Item = (UdpAssociationKey, UdpAssociation)> + '_ {
        self.by_id.clear();
        self.source_counts.clear();
        self.destinations.clear();
        self.entries.drain()
    }

    #[cfg(test)]
    pub(in crate::io_loop) fn get_source(&self, src: &SocketAddr) -> Option<&UdpAssociation> {
        assert!(self.source_counts.get(src).copied().unwrap_or_default() <= 1, "source fixture has multiple owners");
        self.entries.iter().find_map(|(key, association)| (key.src == *src).then_some(association))
    }

    #[cfg(test)]
    pub(in crate::io_loop::udp_assoc) fn values(&self) -> impl Iterator<Item = &UdpAssociation> {
        self.entries.values()
    }

    #[cfg(test)]
    pub(in crate::io_loop::udp_assoc) fn destination_count(&self) -> usize {
        self.destinations.len()
    }
}

#[cfg(test)]
impl<const N: usize> From<[(SocketAddr, UdpAssociation); N]> for UdpAssociations {
    fn from(entries: [(SocketAddr, UdpAssociation); N]) -> Self {
        let mut result = Self::new();
        for (src, association) in entries {
            result.insert(src, association);
        }
        result
    }
}

#[cfg(test)]
impl std::ops::Index<&SocketAddr> for UdpAssociations {
    type Output = UdpAssociation;
    fn index(&self, src: &SocketAddr) -> &Self::Output {
        self.get_source(src).expect("source association exists")
    }
}
